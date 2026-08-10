# WalkSafe 모델 개발·등록 통제

상태: Draft
버전: 0.1.0
문서 묶음: BND-AIML-MODEL
Draft 산출물 유형: AIML-12, AIML-14, AIML-15, AIML-16
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

- Draft로 작성된 유형: AIML-12, AIML-14, AIML-15, AIML-16
- 실행 전 Planned/NOT_RUN 유형: AIML-11, AIML-13
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

<!-- w-ai-2-runtime-evidence:start -->
## W-AI-2 current runtime·artifact evidence

확인 시각은 `2026-07-27T20:37:05+09:00`이다. 파일을 실행하지 않고 bytes와 SHA-256만 물리 확인했다.

| Binding | Exact path | Bytes | SHA-256 | 현재 경계 |
|---|---|---:|---|---|
| unified source PT | `model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt` | 5411845 | `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` | candidate source, 배포 승인 아님 |
| unified TFLite | `apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite` | 9984493 | `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` | primary 개발 후보, `deployment_eligible=false` |
| custom tactile TFLite | `apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite` | 38460640 | `3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780` | fallback, source provenance `UNKNOWN` |
| COCO general TFLite | `apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite` | 10343256 | `776cafdaf1e0bc585a076d4ee3dd71d62f653e0bc2f8504f287e7b5a76c689e1` | fallback, source provenance `UNKNOWN` |
| runtime config | `apps/android/app/src/main/assets/model-config/two_model_runtime.json` | 5508 | `756acb36b1af081ae5a3e484e4f07a158600c9707c058005969b98c91d9add73` | 세 model ID·artifact hash·threshold 후보 |
| TwoModelClassMap | `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TwoModelClassMap.kt` | 3567 | `0f7077a45304338df1ada4e589bd1325c103168418ddb41472af73d61220b83f` | unified 13/custom 3/COCO 80 class 정렬 |

- Exact runtime model set: `unified_walksafe`, `custom_tactile`, `coco_general`.
- Runtime generation set SHA-256: `d8bdfe41cbf76b8f0ef62597920adf35fd80e0acd1acc8cb5f0affad2f99aa07`.
- Physical evidence set SHA-256: `84d6b96659627dcde9fcffcaaa6bcd415f238b8cb677186d69e7b2c5bb51c5db`.
- Promotion/rollback contract SHA-256: `cbb519ec41a1d5f2f76cd1d4a6d332b23a1b3f8f38ab774c6df0d7ef083eee15`.
- `custom_tactile`과 `coco_general`의 독립 source artifact provenance는 `UNKNOWN_PROVENANCE`다.
- 세 모델 모두 `deployment_eligible=false`이며 개발 runtime 연결은 promotion·release 승인이 아니다.
- candidate registry metric은 training history 참고값이며 formal evaluation 결과가 아니다.
- PT↔TFLite 동등성, actual-device validation, formal test, promotion, rollback은 모두 `NOT_RUN` 또는 `NOT_APPROVED`다.
- Trace: `GAP-028`, `GAP-029`, `GAP-040`, `GAP-048` ↔ `AIML-04`, `AIML-14`, `AIML-15`, `AIML-16`, `AIML-17`, `AIML-24`.

<!-- w-ai-2-runtime-evidence:end -->

<a id="aiml-11"></a>
## AIML-11 학습 코드·설정·seed

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 모델 학습·fine-tuning을 수행하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-04, AIML-09, DES-07 |
| 후행 | AIML-12 |

목적: 모델 학습 입력·hyperparameter·환경·난수성을 고정해 실험을 재현한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- source commit·script
- dataset split·hash
- 모델 초기값·구조
- hyperparameter·augmentation
- seed·결정론 설정
- runtime·GPU·명령

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 재현 계약

정식 학습 실행은 source commit, dataset ID·manifest hash, schema version, split hash, Python·CUDA·framework lock, 모델 초기 weight hash, 전체 설정, seed, 명령, 시작·종료시각, 실행환경 ID를 함께 기록한다. 현재 model/train_yolo.py와 관련 스크립트는 후보이며 승인 dataset과 결속해 다시 실행하기 전 재현 완료 근거가 아니다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 정식 학습마다 실행 전에 고정하고 종료 직후 기록 |
| 실행 책임 | ML 개발책임자 |
| 필수 선행 | - 승인 dataset·split hash<br>- 학습 코드 commit<br>- 환경·의존성 lock<br>- 설정·seed |
| 남겨야 할 증거 | - 실행 ID와 명령·설정<br>- 환경·GPU·도구 version<br>- stdout·metric 원자료<br>- 결과 모델·checkpoint SHA-256 |
| 판정 규칙 | 입력·코드·환경·seed와 결과 지문이 한 실행으로 결속되고 같은 절차의 재실행 가능성을 검토해야 완료 |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: source commit·script, dataset split·hash.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-12"></a>
## AIML-12 실험 추적 기록

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 비교 가능한 학습·평가 experiment를 수행한 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-11 |
| 후행 | AIML-13, AIML-14 |

목적: 각 학습·평가 실행의 가설·입력·결과·artifact·결론을 비교 가능하게 보존한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- experiment ID·질문
- code·data·config·seed
- 환경·시작·종료
- metric·curve·log
- checkpoint·artifact hash
- 판정·후속 실험

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실험 기록 규칙

실험은 EXP-YYYYMMDD-NNN ID로 추가하고 목적·가설·부모모델·dataset/split/config/code hash·환경·metric·artifact·결론·실패 원인을 기록한다. 실패한 실행도 삭제하지 않는다. 정식 실험 행은 현재 0개이며 후보 과거 기록은 별도 candidate_history에만 둔다.

정본 등록부: registers/experiment-register.json



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: experiment ID·질문, code·data·config·seed.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-13"></a>
## AIML-13 기준모델·비교실험

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | PLANNED |
| 실제 실행 | NOT_RUN |
| 적용 | CONDITIONAL — 새 모델 후보를 현재 운영·legacy 후보와 비교하는 경우 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-12 |
| 후행 | AIML-14, AIML-15 |

목적: 새 후보가 기존 모델·단순 대안보다 실제로 개선됐는지 동일 프로토콜로 비교한다.

정책 추적: 직접 정책 연결 없음 — 공통정책·선행 산출물에서 상속

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: 직접 결정 없음 — 상위 정책·선행 산출물로 추적

미완료 gate 추적: 직접 gate 없음

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- baseline·candidate ID
- 동일 dataset·protocol
- 전체·class별 metric
- 속도·크기·자원
- 통계·오차 범위
- trade-off·선정 판정

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 실행 대기

승인된 독립 test와 같은 protocol로 현재 기준모델, 새 후보, 안전한 단순 대안을 비교한다. 평가 결과를 본 뒤 metric이나 threshold를 바꾸지 않는다. 현재 비교실험 실행 0건이며 승자나 개선율은 없다.

### 실행 전 계약

| 항목 | 계획 |
|---|---|
| 실행 시점 | 새 모델 후보를 승격하기 전 |
| 실행 책임 | ML 검증책임자 |
| 필수 선행 | - 같은 독립 시험 split<br>- 현재 승인본 또는 명시한 단순 기준모델<br>- AIML-17 평가 protocol |
| 남겨야 할 증거 | - 후보별 model hash<br>- 동일 입력의 전체 지표<br>- 차이와 통계적 불확실성<br>- 퇴행 목록 |
| 판정 규칙 | 필수 안전지표 퇴행이 없고 승인된 개선조건을 충족해야 PASS; 일부 평균 개선으로 치명적 class 퇴행을 상쇄하지 않음 |

현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`입니다. 위 계약은 결과가 아니라 실행 전에 빠뜨리면 안 되는 조건입니다.

완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: baseline·candidate ID, 동일 dataset·protocol.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-14"></a>
## AIML-14 모델 레지스트리

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | REQUIRED — 모델 또는 학습·평가 데이터가 제품·연구 경로에 존재하는 동안 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-12, AIML-13 |
| 후행 | AIML-15, AIML-16, AIML-24 |

목적: 모델 후보·승인·배포·폐기 상태와 데이터·코드·평가·artifact 관계를 한곳에서 추적한다.

정책 추적: FP-001, FP-037, FP-039, FP-049, FP-051

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-126, DEC-MODEL-LATER-SWAP

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW, GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- model ID·version·stage
- artifact 경로·hash
- code·dataset·config
- 평가·승인 근거
- 호환 class·입출력
- 배포·rollback·대체 관계

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 현재 runtime 3-model 등록 계약

정본 등록부는 Android runtime exact set인 `unified_walksafe`, `custom_tactile`, `coco_general` 세 모델만 등록한다. 각 행은 실제 asset path·SHA-256·byte size, 확인 가능한 input/output 계약, class schema namespace·순서, primary/fallback 역할을 runtime config와 Kotlin class map에 결속한다.

| runtime model ID | 역할 | 현재 provenance |
|---|---|---|
| unified_walksafe | primary | candidate PT와 Android TFLite 지문 결속, 미승인 |
| custom_tactile | legacy_two_model tactile fallback component | UNKNOWN_PROVENANCE |
| coco_general | legacy_two_model general fallback component | UNKNOWN_PROVENANCE |

fallback TFLite의 `source_model` 값이 Android asset 자체를 가리키므로 이를 원 학습 artifact나 변환 provenance로 만들지 않는다. 확인할 수 없는 source·변환 이력은 `UNKNOWN_PROVENANCE`로 유지한다.

candidate → evaluated → approved → deployed → retired 순서만 허용한다. 현재 세 모델은 모두 candidate이며 `deployment_eligible=false`, 평가·변환 동등성·Android 기기 성능은 `NOT_RUN`, 승인은 `NOT_APPROVED`, 출시는 `NOT_ELIGIBLE`다. 개발 runtime active 표시는 제품 승인이나 출시 배포를 뜻하지 않으며 AIML-15·16·17·21·23 완료를 주장하지 않는다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: model ID·version·stage, artifact 경로·hash.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-15"></a>
## AIML-15 모델 카드

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | REQUIRED — 모델 또는 학습·평가 데이터가 제품·연구 경로에 존재하는 동안 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-02, AIML-13, AIML-14, AIML-16, AIML-18, AIML-20, AIML-23 |
| 후행 | AIML-25, WS-10 |

목적: 모델의 목적·성능·사용 조건·금지 용도·안전 한계를 제품·운영자에게 전달한다.

정책 추적: FP-001, FP-021, FP-037, FP-039, FP-049

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-MODEL-LATER-SWAP, DEC-OBSTACLE-CLASS-SCOPE

미완료 gate 추적: GATE-RAW-COLLECTION-RELEASE-REVIEW

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- 모델 ID·버전·소유자
- intended use·out of scope
- 학습 데이터 요약
- 평가 결과·threshold
- 편향·한계·안전 고지
- 배포·모니터링
- AIML-16 모델 artifact ID·version·SHA-256를 입력으로 고정한 model card binding
- AIML-18·20·23 독립 평가·안전·threshold 결과의 승인본 ID와 요약

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 후보 모델 카드

현재 후보는 Android에서 13종 물체 후보를 만들기 위한 YOLO 계열 모델이다. 거리·위험 단계·사용자 행동·안전 여부·자동신고를 단독 결정하지 않는다. 독립 test, content hash 기반 dataset, sequence-safe split, 낮은 curb_step·uneven_sidewalk recall 보완, 변환 동등성, 지원기기 성능이 미완료이므로 안전 보장·출시 적합·지원 class 확정을 주장하지 않는다.

| 카드 항목 | 현재 후보 사실 |
|---|---|
| 모델 ID | walksafe-13cls-yolo26n-img768-epoch270-20260708 |
| 구조 | YOLO26n 계열 객체 탐지 후보 |
| source 파일 | model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt |
| source SHA-256 | a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669 |
| Android 파일 | apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite |
| Android SHA-256 | 92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19 |
| 입력 | float32, 1×768×768×3 후보 |
| 출력 | float32, 1×300×6 후보 |
| class | AIML-04의 13개 후보 순서 |
| 사용 목적 | FP-019 물체 후보와 관측 근거 생성 |
| 금지 목적 | 거리·위험 단계·행동·안전함·자동신고의 단독 결정, 사람 식별 |
| 현재 단계 | CANDIDATE, deployment_eligible=false |

기존 registry에는 precision 0.62087, recall 0.56716, mAP50 0.55403, mAP50-95 0.41651이 적혀 있다. 이 값은 연결 dataset manifest와 results.csv가 현재 없고 독립 test도 아니므로 이번 기준선의 정식 평가값으로 사용하지 않는다. 알려진 blocker는 독립 test 없음, dataset content hash 없음, 촬영 sequence split 누수, curb_step·uneven_sidewalk recall 부족이다.

예상 사용자는 보행 중 위험 정보를 음성·진동으로 받는 사용자이지만, 모델이 놓칠 수 있으므로 모델만 믿고 걸어도 된다고 설명하지 않는다. 조명·날씨·흔들림·가림·거리·기기 차이와 학습자료 분포 밖 장면은 미검증 제한이다.



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 모델 ID·버전·소유자, intended use·out of scope.
- 필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.
- 상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.
- 지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.

갱신 조건:

- 데이터·schema·split·코드·모델·threshold·runtime 변경
- 연결된 상위 기준선·사용자 승인 결정·외부 의무 변경
- 검토에서 사실 오류·누락·stale 판정 또는 유효기간 만료

변경·대체·폐기: 승인 전에는 Draft revision으로 고치고, 승인 뒤 의미·범위·임계값·데이터나 모델 지문이 바뀌면 변경요청과 영향분석을 거쳐 새 버전을 만든다. 이전 정본은 Superseded로 바꾸고 보존기간 종료 뒤 Archived 처리한다. 실행 증거는 덮어쓰지 않는다.

<a id="aiml-16"></a>
## AIML-16 모델 파일·버전·해시

| 구분 | 현재 값 |
|---|---|
| 산출물 상태 | DRAFT |
| 실제 실행 | NOT_RUN |
| 적용 | REQUIRED — 모델 또는 학습·평가 데이터가 제품·연구 경로에 존재하는 동안 활성 |
| 작성 | ML책임자 |
| 검토 | 데이터책임자, QA책임자, 접근성·안전책임자 |
| 승인 | 제품책임자, 아직 미승인 |
| 선행 | AIML-14 |
| 후행 | AIML-15, AIML-17, AIML-18, AIML-21, AIML-24, REL-03, REL-06, WS-10 |

목적: 실제 PT·ONNX·TFLite artifact를 모델 ID·형식·변환·무결성과 정확히 결속한다.

정책 추적: FP-037, FP-039, FP-051

공통정책 추적: NPC-RAW-ORIGINAL-COLLECTION, NPC-DATA-LIFECYCLE, NPC-SERVER-STORAGE-CAPACITY

정렬 결정 추적: DEC-IBQ-126

미완료 gate 추적: GATE-SINGLE-ADMIN-RECOVERY-DRILL

OPEN 이슈 추적: 직접 OPEN 이슈 없음

반드시 다룰 내용:

- model ID·semantic version
- format·opset·quantization
- 파일 경로·크기·SHA-256
- source checkpoint·변환 도구
- 입출력 contract
- 승인·배포 상태

필요 입력:

- 승인된 모델 역할·class·안전 정책
- 데이터 출처·권리·manifest·split 원자료
- 학습 코드·설정·모델 artifact·평가 환경

### 파일·지문 원칙

등록부는 실제 존재하는 PT·TFLite와 runtime 설정의 SHA-256을 다시 계산한다. 레지스트리의 선언값과 다르면 생성에 실패한다. 파일 존재와 지문 일치는 동일 파일임을 보일 뿐 학습 재현·정확도·TFLite 동등성·Android 적합성을 증명하지 않는다.

정본 등록부: registers/model-register.json



완료·승인 기준:

- 다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: model ID·semantic version, format·opset·quantization.
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
