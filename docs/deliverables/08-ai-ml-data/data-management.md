# WalkSafe 데이터 관리·데이터셋 통제

상태: Draft
버전: 0.1.0
문서 묶음: BND-AIML-DATA
Draft 산출물 유형: AIML-01, AIML-02, AIML-03, AIML-04, AIML-06, AIML-07
승인 상태: NOT_APPROVED
정책 기준선: PB-WALKSAFE-FEATURE-POLICY-1.0.1
기반 정책 내용 지문: e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28
1.0.1 기준선 manifest 내용 지문: 8fdeab58e8c901a29f4380de373cdfd2b67350cd43dafa765246b4f6c9f77244
FP-035 정책 활성화: current 1.0.1 overlay `APPROVED/EFFECTIVE/COMMITTED`; PB-WALKSAFE-FEATURE-POLICY-1.0.0과 WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001은 `HISTORICAL_PRE_ACTIVATION_ONLY`
결정 지문: 16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538
실행 검증: NOT_RUN
출시 상태: NOT_ELIGIBLE
현행 제품: Android 사용자 앱과 별도 Android 관리자 앱
Web/PWA: LEGACY_REFERENCE_ONLY
민감 원본: Git 저장 금지

## 이 문서를 읽는 방법

Draft는 작성 가능한 계획·규칙·등록부 구조를 만들었다는 뜻입니다. 실제 학습이나 시험이 끝났다는 뜻이 아닙니다. Planned/NOT_RUN 항목은 실행 절차만 준비됐고 결과·합격·증거가 아직 없다는 뜻입니다.

## 현재 묶음 상태

- Draft로 작성된 유형: AIML-01, AIML-02, AIML-03, AIML-04, AIML-06, AIML-07
- 실행 전 Planned/NOT_RUN 유형: AIML-05, AIML-08, AIML-09, AIML-10
- 실제 학습·평가·운영 실행 수: 0
- 실제 PASS 수: 0
- 승인된 출시 모델 수: 0
- 남은 정책 gate: 5개, 모두 NOT_RUN·미면제
- FP-035 이동통신망 조건: current 1.0.1 overlay `APPROVED/EFFECTIVE/COMMITTED`. 일반 활동원본은 보행 중 미전송, 정지 뒤 명시 선택 시에만 허용된 이동통신망 전송, 미선택 시 Wi-Fi 전송. 구현 적합성 재검증·network 분기·phone queue 바이트 한도·관련 시험과 gate는 `OPEN/NOT_RUN`이며 정책 활성화만으로 구현 완료를 주장하지 않음

## GAP-043·FP-034 현재 데이터 workflow 통제

통제 범위: `GAP-043`의 문서·등록부 작성 가능 범위만 보완한다. `RQ-FP-034-001`, `FP-034`, `AIML-01`~`AIML-04`를 `PB-WALKSAFE-FEATURE-POLICY-1.0.1`과 `WS-DATA-MODEL-SOURCE-CURRENT-STATE-20260727-001`에 결속한다. 이 절은 실제 source 취득, license·동의·privacy 확인, dataset materialization, 품질·split·누수 검사, 학습·평가·시험 완료를 뜻하지 않는다.

### 현재 확인 사실

| 항목 | 현재 값 |
|---|---|
| 선언된 current source entry | 6개: COCO 2017, AIHub 186·513·189 Surface·572 승인 subset·189 수동 승인 subset |
| 고유 provider/dataset pair | 5개. AIHub 189의 두 entry는 같은 pair로 정규화 |
| 권리 확인 | 0/5 pair, `NOT_VERIFIED` |
| privacy 검토 | 0/5 pair, `NOT_VERIFIED` |
| 직접촬영 자료 포함 여부 | `UNKNOWN` |
| 사용자제공 자료 포함 여부 | `UNKNOWN` |
| materialized manifest | 선언 경로가 현재 작업공간에 없음, 선언 hash 재검증 불가 |
| data.yaml | 선언 경로가 현재 작업공간에 없음 |
| 품질·라벨·split·누수·독립 시험 | `NOT_RUN` 또는 `NOT_VERIFIED` |
| 학습·출시 적격 | 아니오 |

6개 current source entry는 기존 data-source register의 6개 역사적 metadata artifact entry와 다른 집합이다. source 목록 완전성도 아직 확정하지 않았으며, 직접촬영·사용자제공 자료가 없다고 추정하지 않는다.

### FP-034 workflow와 fail-closed 경계

| 단계 | 허용 | 금지 | fail-closed | owner·due predicate |
|---|---|---|---|---|
| `FP034-WF-01 SOURCE_NOMINATION` | 확인된 선언·역사 metadata를 후보로 기록 | 선언 수량을 재현 완료, 목록 완전성, 권리 확인으로 표현 | 후보·`UNKNOWN/NOT_VERIFIED` 유지, materialization 차단 | 데이터책임자, 다음 source 취득 또는 materialization 전 |
| `FP034-WF-02 CONSENTED_COLLECTION_START` | 승인 항목표, 별도 연구 동의, 활성 보행, OS 권한·암호화·용량 조건이 모두 참인 범위만 수집 | 동의·항목표·권한 없이 수집, 연구 동의 거부로 핵심 보행 제한 | 원본 수집 미시작 또는 즉시 중지; 가능한 핵심 탐지·길안내 유지 | 데이터책임자, 직접촬영·사용자제공 수집 전 |
| `FP034-WF-03 ENCRYPTED_QUEUE_AND_STOPPED_TRANSFER` | 단말 암호화 보관; 정지 뒤 이동통신망 명시 선택자는 허용된 이동통신망, 미선택자는 Wi-Fi 전송 | 보행 중 일반 활동원본 전송, 허용되지 않은 망 전송, 무승인 제3자 제공 | 암호화 대기열 유지와 보존·삭제 적용; 전송 완료 처리 금지 | Android·backend 책임자, 관련 통합시험 전 |
| `FP034-WF-04 SERVER_QUARANTINE_AND_SOURCE_GATE` | 검역 격리 뒤 source·취득·license·학습·재배포·privacy·consent 심사 | 검역·동의·출처·권리 확인 전 학습자료 승격 | 검역·후보 상태와 `promotion_eligible=false` 유지 | 법무·privacy 검토자, source promotion 또는 materialization 전 |
| `FP034-WF-05 MATERIALIZATION_QUALITY_AND_SPLIT_GATE` | source gate 통과 자료만 불변 manifest로 만들고 품질·라벨·sequence-safe split·누수 검사 | 누락 manifest, 재계산하지 않은 수량, 미검증 split을 재현·독립시험 근거로 사용 | `training_or_release_eligible=false` 유지 | 데이터·QA책임자, 학습 또는 독립 평가 명령 전 |
| `FP034-WF-06 TRAINING_EVALUATION_AND_RELEASE_GATE` | 모든 선행 gate와 독립 검토를 통과한 고정 dataset/model hash만 승인 제출 | 후보 지표·파일 존재를 정식 평가·승인 학습·출시 적합성으로 승격 | `NOT_RUN/NOT_ELIGIBLE` 유지 | 제품책임자·독립검토자, TST-22 또는 REL-02 전 |

### owner·due predicate

| ID | owner | due predicate | 완료 predicate | 현재 상태 |
|---|---|---|---|---|
| `FP034-OWNER-DUE-01` | 데이터책임자 | `BEFORE_NEXT_SOURCE_ACQUISITION_OR_DATASET_MATERIALIZATION` | source 목록 완전성, 6 entry/5 pair, 직접촬영·사용자제공 포함 여부를 증거 ID로 확정 | `OPEN_EVIDENCE_FACT_REQUIRED` |
| `FP034-OWNER-DUE-02` | 법무·privacy 검토자 | `BEFORE_ANY_SOURCE_PROMOTION_OR_DATASET_MATERIALIZATION` | 실제 사용 source별 취득·license·학습·재배포·privacy·consent·철회 영향 확인 | `OPEN_ZERO_OF_FIVE_UNIQUE_PAIRS_VERIFIED` |
| `FP034-OWNER-DUE-03` | 데이터·QA책임자 | `BEFORE_ANY_TRAINING_OR_INDEPENDENT_EVALUATION_COMMAND` | manifest·data.yaml·content hash·품질·라벨·sequence-safe split·누수 증거 등록 | `OPEN_MANIFEST_AND_DATA_YAML_MISSING` |
| `FP034-OWNER-DUE-04` | 제품책임자·독립검토자 | `BEFORE_TST_22_RELEASE_READINESS_OR_REL_02_APPROVAL` | `GATE-RAW-COLLECTION-RELEASE-REVIEW` 실행과 고지·동의·권리행사·잔여 위험 승인 | `OPEN_GATE_NOT_RUN_NOT_WAIVED` |

현재 닫을 수 있는 것은 workflow 규칙, fail-closed 경계, 책임자와 due predicate의 문서·등록부 결속뿐이다. 위 OPEN predicate의 실제 충족 증거가 들어오기 전에는 `AIML-01`~`AIML-04` 승인, dataset 재현, 학습·시험 또는 `GAP-043`의 실행 완료를 주장하지 않는다.

## 쉬운 용어

- dataset: 모델이 학습하거나 평가할 자료 묶음
- label: 사진 속 물체의 종류와 위치를 표시한 정답
- split: 학습용·조정용·독립 시험용 자료를 서로 섞이지 않게 나눈 것
- hash 또는 파일 지문: 파일이 같은지 확인하는 긴 값
- threshold: 모델 점수가 어느 정도일 때 후보로 인정할지 정한 기준
- drift: 운영 중 입력이나 결과의 분포가 기준 시점과 달라지는 현상
- candidate: 조사·재검증 대상이며 승인·배포가 확정되지 않은 후보

<a id="aiml-01"></a>
## AIML-01 데이터 관리계획

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 학습·재학습·평가 데이터를 수집·변경하는 작업선을 재개할 때 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | DSC-12, REQ-08, REQ-10, REQ-15 |
| 후행 | AIML-02, AIML-03, AIML-04, AIML-26 |

목적: 학습·평가 데이터의 수집·권리·품질·버전·접근·보존을 전 생명주기로 통제한다.

정책 추적: FP-027, FP-031, FP-032, FP-034, FP-035, FP-036, FP-038, FP-046, FP-053

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-AUTO-REPORT-TIMING, DEC-IBQ-091, DEC-IBQ-092, DEC-IBQ-098, DEC-ONDEVICE-AUDIO-HAPTIC-DATA

미완료 gate 추적: GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 구현·검증 추적: ISS-POLICY-FP035-NETWORK-001 (`POLICY_RESOLVED_IMPLEMENTATION_REVALIDATION_OPEN`), CR-0002, RAID-011

FP-035 정책 이력: PB-WALKSAFE-FEATURE-POLICY-1.0.0과 WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001은 `HISTORICAL_PRE_ACTIVATION_ONLY`; current 1.0.1 overlay는 `APPROVED/EFFECTIVE/COMMITTED`

반드시 다룰 내용:

- 데이터 목적·범위
- 출처·수집·권리
- 저장·접근·보안
- 정제·라벨·분할·버전
- 품질·변경 관리
- 보존·폐기·책임

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 확정된 데이터 생명주기

- 별도 동의한 활성 보행에서 압축 원본 영상·음성·정확 위치·센서·탐지·경로·신고·성능 자료를 수집한다. 얼굴·번호판·주변 목소리를 자동으로 가린 원본으로 바꾸지 않는다.
- 서버 수신이 확인된 휴대전화 사본은 24시간 안에 삭제하고, 미전송 휴대전화 원본은 30일을 넘기지 않는다.
- 서버 수신·검역 원본은 14일, 일반·자동신고 원본은 180일, 승인 학습자료·라벨·고정 검증자료는 승인 뒤 3년, 운영 백업은 35일 보관한다.
- 전체 삭제 요청은 휴대전화 24시간, 서버 원본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일 안에 처리한다.
- 원본·동의서·정확 위치는 Git에 넣지 않는다. Git에는 통제 저장소 ID, 파일 지문, 권한, 보존기간, 처리상태만 둔다.

서버 주 원본 300 GiB 기준으로 70% 관리자 경고, 85% 신규 현장시험 참여자 추가 중단, 95% 만료자료 정리 후 새 원본수집 보류, 100% 새 학습자료·자동신고 후보 생성을 보류한다. 기존 암호화 자료와 실시간 탐지·길안내는 유지한다. FP-035의 current 1.0.1 overlay는 `APPROVED/EFFECTIVE/COMMITTED`이며, 일반 활동원본은 보행 중 전송하지 않고 정지 뒤 이동통신망 명시 선택 시에만 허용된 이동통신망으로 전송하며 미선택 시 Wi-Fi만 사용한다. 정책 문구는 활성화됐지만 구현 적합성 재검증, network 분기, `GATE-PHONE-QUEUE-BYTE-LIMIT`, 관련 통합시험과 출시 gate는 `OPEN/NOT_RUN`이다. 원본수집의 출시 적합성도 `GATE-RAW-COLLECTION-RELEASE-REVIEW`가 끝날 때까지 주장하지 않는다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 데이터 목적·범위, 출처·수집·권리.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-02"></a>
## AIML-02 데이터셋 카드

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | REQUIRED — 모델 또는 학습·평가 데이터가 제품·연구 경로에 존재하는 동안 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-01 |
| 후행 | AIML-05, AIML-15, AIML-17 |

목적: 특정 데이터셋 버전의 구성·용도·분포·편향·제약을 모델 사용자에게 투명하게 설명한다.

정책 추적: FP-038

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- dataset ID·버전·해시
- 목적·허용·금지 용도
- 출처·수집 기간·지역
- 규모·class·분포
- 정제·라벨·분할
- 편향·한계·권리

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 현재 데이터셋 카드 결론

현 저장소에는 여러 과거 후보 manifest가 있으나, 승인 학습 데이터셋의 단일 ID·전체 파일 지문·sequence 안전 split·권리 검토를 함께 충족한 정본은 확인되지 않았다. 모델 후보가 가리키는 materialized_manifest.csv도 현재 작업공간에 없다. 따라서 이 카드는 후보 데이터의 존재와 결손만 설명하며 학습 재현성이나 제품 적합성을 주장하지 않는다.

허용 용도는 로컬 조사·정제·재검증 준비이고, 금지 용도는 출시 성능 주장, 독립 시험 대체, 권리 미확인 원본 공유, Web/PWA 결과의 Android 근거 전용이다.

| 카드 항목 | 현재 확인값 |
|---|---|
| 임시 ID | DS-CANDIDATE-FROM-MODEL-REGISTRY |
| 연결 manifest | datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/materialized_manifest.csv |
| manifest 존재 | 아니오 |
| 선언된 manifest 지문 | 5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6, 현재 파일이 없어 재검증 불가 |
| 후보 class 수 | 13 |
| 파일 수·지역·기간·분포 | 확인 불가 |
| 권리·동의 | NOT_VERIFIED |
| sequence-safe split | NOT_RUN |
| 품질·라벨·누수 검사 | NOT_RUN |
| 승인·학습 재현·출시 사용 | 불가 |



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: dataset ID·버전·해시, 목적·허용·금지 용도.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-03"></a>
## AIML-03 데이터 출처·라이선스·동의 기록

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 외부·사용자·기관 데이터 또는 새로운 데이터 source를 사용하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자, 법무·라이선스검토자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-01, REQ-15 |
| 후행 | AIML-06, AIML-10 |

목적: 모든 이미지·라벨·metadata의 사용·변형·재배포·학습 권리를 항목별로 증명한다.

정책 추적: FP-031, FP-032, FP-034, FP-035, FP-036, FP-038, FP-046, FP-047

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-AUTO-REPORT-TIMING, DEC-DATA-SHARING, DEC-IBQ-121

미완료 gate 추적: GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 구현·검증 추적: ISS-POLICY-FP035-NETWORK-001 (`POLICY_RESOLVED_IMPLEMENTATION_REVALIDATION_OPEN`), CR-0002, RAID-011

FP-035 정책 이력: PB-WALKSAFE-FEATURE-POLICY-1.0.0과 WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001은 `HISTORICAL_PRE_ACTIVATION_ONLY`; current 1.0.1 overlay는 `APPROVED/EFFECTIVE/COMMITTED`

반드시 다룰 내용:

- source ID·제공자·URL
- 취득일·원본 버전
- license·약관·동의
- 허용·금지 행위
- attribution·보존 증거
- 철회·삭제 영향

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 출처·권리 확인 방법

각 출처는 제공자·원 URL·취득일·원본 버전·라이선스 원문 지문·학습/변형/재배포 허용범위·고지 의무·동의 근거·철회 영향을 한 행으로 기록한다. 현재 후보 행은 경로와 지문만 확인했으며 권리와 개인정보 검토는 NOT_VERIFIED다. 원본이나 동의서 대신 통제 저장소 참조만 기록한다.

정본 등록부: registers/data-source-register.json



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: source ID·제공자·URL, 취득일·원본 버전.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-04"></a>
## AIML-04 데이터 스키마·버전

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | REQUIRED — 모델 또는 학습·평가 데이터가 제품·연구 경로에 존재하는 동안 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-01, REQ-08 |
| 후행 | AIML-05, AIML-07, AIML-09, AIML-11 |

목적: 이미지·라벨·class·metadata 형식과 호환성 규칙을 기계 검증 가능한 계약으로 만든다.

정책 추적: FP-021, FP-034, FP-035, FP-045, FP-046, FP-053

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-093

미완료 gate 추적: GATE-CLOUD-COST-MEASUREMENT, GATE-PHONE-QUEUE-BYTE-LIMIT, GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SERVER-CAPACITY-STATE-CONTRACT, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 구현·검증 추적: ISS-POLICY-FP035-NETWORK-001 (`POLICY_RESOLVED_IMPLEMENTATION_REVALIDATION_OPEN`), CR-0002, RAID-011

FP-035 정책 이력: PB-WALKSAFE-FEATURE-POLICY-1.0.0과 WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001은 `HISTORICAL_PRE_ACTIVATION_ONLY`; current 1.0.1 overlay는 `APPROVED/EFFECTIVE/COMMITTED`

반드시 다룰 내용:

- schema ID·version
- 필드·type·단위·제약
- class ID·명칭·매핑
- 파일·디렉터리 layout
- 호환·migration
- validator·예시

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 현재 Android runtime class-schema 계약

권위 입력은 `two_model_runtime.json`과 `TwoModelClassMap.kt`다. 두 입력의 모델 exact set과 class 순서가 다르면 builder가 실패한다. 현재 계약은 `unified_walksafe` 13개, `custom_tactile` 3개, `coco_general` 80개이며 전체 순서·qualified ID는 `registers/dataset-register.json`에 기록한다.

| runtime model ID | class 수 | numeric ID namespace |
|---|---:|---|
| unified_walksafe | 13 | walksafe.unified_walksafe.class_id |
| custom_tactile | 3 | walksafe.custom_tactile.class_id |
| coco_general | 80 | coco.coco_general.class_id |

numeric class ID는 모델별 namespace 안에서만 의미가 있다. 같은 canonical name이라도 서로 다른 모델의 numeric ID를 같다고 해석하지 않는다. canonical name은 runtime label을 trim하고 ASCII lowercase로 바꾼 뒤 영숫자가 아닌 연속 문자를 underscore 하나로 바꾸고 양끝 underscore를 제거한다. runtime label과 canonical name을 모두 보존한다.

class 추가·삭제·순서 변경은 모두 breaking major migration이다. 새 schema major version, runtime config와 Kotlin map의 동시 갱신, 영향 모델 재-export, AIML-21 변환 동등성 재검증이 필요하다. 현재 migration·재-export·동등성 검증은 `NOT_RUN`이며 AIML-05~13·16·17·21·23 완료를 주장하지 않는다.

이미지는 불변 sample_id, source_id, capture_group_id, content_sha256, width, height, captured_at 범주값, consent_record_id, storage_object_id를 가진다. 라벨은 sample_id, model-specific qualified class ID, normalized bbox 좌표, occlusion, truncation, reviewer_state, annotation_version을 가진다. 정확 위치와 사람 식별 가능 원본값은 Git용 schema에 직접 넣지 않고 통제 저장소 ID로 참조한다.

bbox는 x중심·y중심·너비·높이를 이미지 크기 0~1 범위로 정규화한다. 알 수 없는 class, 음수 크기, 이미지 밖 좌표, 중복 sample_id는 validator가 거부한다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: schema ID·version, 필드·type·단위·제약.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-05"></a>
## AIML-05 데이터 품질보고서

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | REQUIRED — 모델 또는 학습·평가 데이터가 제품·연구 경로에 존재하는 동안 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-02, AIML-04 |
| 후행 | AIML-06 |

목적: 완전성·정확성·중복·분포·손상·개인정보 품질을 버전별로 측정하고 판정한다.

정책 추적: FP-034, FP-036, FP-038, FP-046

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-121

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 대상 dataset·hash
- 검사 도구·규칙
- 누락·손상·형식 오류
- class·source 분포
- 중복·이상치·privacy
- 결함·제외·합격 판정

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

완전성·손상·형식·중복·class/source 분포·개인정보·권리 검사를 아직 정식 데이터셋에 실행하지 않았다. 대상 dataset ID와 전체 content manifest가 고정된 뒤 동일 도구 버전으로 실행하고, 결과는 append-only evidence로 등록한다. 현재 결과·합격 판정·결함 수는 없다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 학습·검증·시험 데이터셋 버전을 승인하기 전과 자료구성이 바뀔 때 |
| 실행 책임 | 데이터책임자 |
| 필수 선행 | - 불변 dataset manifest와 파일 지문<br>- 출처·권리·동의 상태<br>- 승인된 품질항목과 표본추출 방법 |
| 남겨야 할 증거 | - 전체·출처·class별 수량과 결측·중복·손상 통계<br>- 검사 도구·설정·실행 log<br>- 제외 목록과 조치·재검사 결과 |
| 판정 규칙 | 사전에 정한 품질항목을 모두 계산하고 중대한 결측·손상·권리 미확인이 0건이며 남은 한계가 승인돼야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 대상 dataset·hash, 검사 도구·규칙.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-06"></a>
## AIML-06 정제·제외 기준

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 데이터 정제·제외·재라벨 작업을 수행하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-03, AIML-05 |
| 후행 | AIML-07, AIML-09 |

목적: 어떤 샘플을 수정·보류·제외하고 이유와 재현 경로를 남길지 일관되게 정한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- blur·노출·해상도 기준
- 잘못된 class·box 기준
- 개인정보·권리 제외
- 중복·누수 제외
- keep·fix·hold·drop 판정
- 결정 기록·재검토

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### keep·fix·hold·drop 규칙

- keep: 출처·동의·형식·class·bbox가 모두 확인된 샘플
- fix: 원본 권리는 유효하고 라벨 또는 메타데이터의 수정 범위가 명확한 샘플
- hold: 작은 객체 기준, 권리, 동의, sequence 그룹, 개인정보 처리가 미결정인 샘플
- drop: 손상 파일, 허용되지 않은 출처, 철회 대상, 복구 불가능한 class/bbox 오류

결정은 sample_id와 사유 코드로 남기며 원본 파일을 조용히 덮어쓰지 않는다. blur·노출·해상도 숫자는 데이터 품질 실행 전 임의로 만들지 않고 protocol revision에서 정한다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: blur·노출·해상도 기준, 잘못된 class·box 기준.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-07"></a>
## AIML-07 라벨링 지침

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 신규 라벨링 또는 class 정의 변경을 수행하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-04, AIML-06 |
| 후행 | AIML-08 |

목적: 13개 class와 손상·위험 객체의 포함 범위·bbox 규칙·애매 사례를 작업자 간 동일하게 한다.

정책 추적: FP-038

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-122

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- class 정의·우선순위
- positive·negative 경계
- bbox·occlusion·truncation
- 다중 객체·애매 사례
- 도구·저장 형식
- 예시·질의·개정

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 라벨링 지침

13개 후보 class의 이름과 순서는 runtime 후보 설정에 결속한다. 보이는 물체의 실제 경계만 bbox로 표시하며, 가림·화면 잘림·작은 객체·여러 객체는 각각 상태를 기록한다. 같은 종류라는 이유로 서로 다른 객체를 합치지 않는다. 정상 점자블록을 파손으로 바꾸거나 애매한 장애물을 임의 class로 확정하지 않고 hold와 질문 기록을 사용한다.

신규 작업 전 positive/negative 경계와 class별 그림 예시를 개인정보 없는 승인 예시 세트로 확정해야 한다. 과거 relabel 자료는 후보 참고이며 새 기준의 품질검사를 통과하기 전 승격하지 않는다.

- 사람·자전거·자동차·오토바이·버스·화물차는 실제로 보이는 개체별로 각각 표시하고, 사진 밖 부분을 상상해 박스를 넓히지 않는다.
- 신호등은 기구 전체를 표시하되 색상 상태는 이 13-class label에 덧붙이지 않는다.
- 정상 점자블록과 파손 점자블록은 외관 근거가 분명한 경우에만 구분한다. 마모·그림자·오염만으로 파손을 확정하지 않는다.
- 횡단보도는 보이는 도색 범위를 기준으로 하며 도로 전체를 박스로 잡지 않는다.
- curb_step과 uneven_sidewalk는 의미가 겹치면 하나를 임의 선택하지 않고 hold한다. 승인 예시에서 물리적 경계와 표면 불균일의 구분을 먼저 고정한다.
- e_scooter_obstruction은 킥보드 존재가 아니라 보행 통로를 막는 상태가 보이는 경우를 후보로 한다. 통행 방해 여부가 사진 한 장으로 불명확하면 hold한다.
- 가려짐과 화면 잘림은 별도 flag로 남기며 보이는 부분이 너무 적어 class를 알 수 없으면 label하지 않고 질문 대기열에 넣는다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: class 정의·우선순위, positive·negative 경계.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-08"></a>
## AIML-08 라벨링 품질검사

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 사람·AI 라벨링 결과를 학습 데이터로 승격하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-07 |
| 후행 | 없음 |

목적: 라벨 정확도와 작업자 일치도를 표본·이중검수·오류 분석으로 확인한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 검사 표본·추출 방식
- gold set·이중 라벨
- class·bbox 오류 기준
- 일치도·정확도 지표
- 오류 유형·재작업
- 검수자·승인·증거

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

gold set, 이중 라벨 표본, 추출 seed, class·bbox 오류 기준과 합격선을 먼저 동결한 뒤 검사한다. 현재 정식 표본 추출·이중 검수·일치도 계산·재작업 결과는 없다. 과거 검토 파일의 존재를 이번 기준선의 PASS로 바꾸지 않는다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 라벨 묶음을 학습 또는 독립 평가에 사용하기 전 |
| 실행 책임 | 라벨링 품질검사자 |
| 필수 선행 | - AIML-07 지침 version<br>- 가명화된 표본 목록<br>- 검사자와 라벨 작성자의 역할 구분 |
| 남겨야 할 증거 | - class·난이도별 표본<br>- 일치·누락·경계 오류 집계<br>- 불일치 조정 기록과 수정 version |
| 판정 규칙 | 승인된 표본수와 오류기준을 충족하고 치명적 class 혼동·누락을 모두 조치한 경우만 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 검사 표본·추출 방식, gold set·이중 라벨.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-09"></a>
## AIML-09 학습·검증·시험 분할과 해시

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 새 dataset version으로 학습·독립 평가를 수행하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-04, AIML-06 |
| 후행 | AIML-10, AIML-11, AIML-17, AIML-18 |

목적: 동일 장면·sequence·source 누수를 막은 독립 split을 파일·그룹·해시로 고정한다.

정책 추적: FP-036, FP-038, FP-042, FP-049

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-097, DEC-IBQ-120, DEC-IBQ-122

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- split 단위·비율·seed
- sequence·subject·source grouping
- manifest·파일 hash
- class·source 분포
- 재분할 절차
- 기준선·검증 결과

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

train/validation/test는 파일이 아니라 capture_group·연속 영상·장소·source 단위로 먼저 묶은 뒤 그룹 전체를 한 split에 둔다. dataset content manifest가 없으므로 현재 split 비율·seed·파일 hash·분포 결과는 확정하지 않았다. 독립 test를 학습·threshold 선택에 다시 사용하지 않는다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 학습 실행을 시작하기 전과 dataset version이 바뀔 때 |
| 실행 책임 | 데이터 엔지니어 |
| 필수 선행 | - 불변 sample·capture_group·source ID<br>- 분할 비율과 독립 시험 잠금 규칙 |
| 남겨야 할 증거 | - train·validation·test manifest<br>- 각 manifest SHA-256<br>- group 겹침 0건 검사 결과 |
| 판정 규칙 | 동일 보행·연속촬영·원본 파생물이 분할 사이에 겹치지 않고 독립 시험 묶음이 잠겨야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: split 단위·비율·seed, sequence·subject·source grouping.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-10"></a>
## AIML-10 데이터 누수 점검

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — dataset split을 생성·변경하거나 외부 데이터를 병합한 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-03, AIML-09 |
| 후행 | 없음 |

목적: train·validation·test 사이 동일·근접중복·연속장면·파생본 침범을 탐지한다.

정책 추적: FP-038

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-122

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 누수 가설·위험
- exact·perceptual hash
- sequence·경로·metadata 검사
- 임계값·도구·버전
- 발견 pair·조치
- 재검사·잔여 한계

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

동일 content hash, 유사 이미지, 같은 capture sequence, 파생 crop, source 중복, 학습 데이터와 독립 test의 교차를 검사한다. 현재 정식 split이 없어 누수 0건 또는 합격을 주장할 수 없다. 발견 건은 원본 그룹 단위 재분할과 새 dataset version으로 처리한다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 분할 뒤, 학습 전, 그리고 평가 결과를 승인하기 전 |
| 실행 책임 | ML 검증책임자 |
| 필수 선행 | - AIML-09 분할 manifest<br>- 원본·파생·중복을 찾을 content/group key |
| 남겨야 할 증거 | - 정확·유사·group 중복 검사 원자료<br>- 누수 후보별 판정<br>- 재분할·재실행 기록 |
| 판정 규칙 | 확정 누수 0건이고 모든 후보의 근거 있는 판정과 재검사가 끝나야 PASS |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 누수 가설·위험, exact·perceptual hash.
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
