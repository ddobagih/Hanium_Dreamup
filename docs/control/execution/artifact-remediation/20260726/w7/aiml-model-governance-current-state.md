# W7 AIML 모델 거버넌스 현재 상태

- 기준일: `2026-07-27`
- 범위: `DLV-AIML-06`, `DLV-AIML-07`, `DLV-AIML-10`, `DLV-AIML-14`, `DLV-AIML-15`, `DLV-AIML-16`, `DLV-AIML-17`, `DLV-AIML-23`
- 판정: 내부 완료 후보 1건, 내부 GAP 7건
- `source_commit`: `null`
- `build_id`: `null`
- 권한 경계: 이 문서는 현재 상태와 내부 후보를 고정하며 평가 완료, 승인, 배포 적격을 주장하지 않는다.

## 산출물 판정

| ID | 판정 | 독립 검토 | 현재 GAP 또는 경계 | 다음 조치 |
|---|---|---|---|---|
| `DLV-AIML-06` | `INTERNAL_GAP` | `NOT_READY` | 데이터 정제의 keep/fix/hold/drop 수치 기준과 승인 기준이 설정되지 않았다. | 수치 기준을 동결하고 적용 결과 및 승인 근거를 생성한다. |
| `DLV-AIML-07` | `INTERNAL_GAP` | `NOT_READY` | 라벨링 지침에 연결된 승인된 positive/negative 예시 세트가 없다. | 클래스별 승인 예시와 반례를 작성하고 검토 승인을 기록한다. |
| `DLV-AIML-10` | `INTERNAL_GAP` | `NOT_READY` | capture/perceptual/sequence/metadata leakage 검사가 실행되지 않았고 결과가 없다. | 네 종류 누수 검사를 실제 데이터 스냅샷에 실행하고 증거와 판정을 고정한다. |
| `DLV-AIML-14` | `OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW` | `PENDING` | 런타임 3개 모델의 실제 자산·계약·역할·fallback 현황은 내부 후보 수준으로 결속됐지만 독립 검토 전이다. | 독립 검토에서 등록부-자산-런타임 구성 일치를 확인한다. |
| `DLV-AIML-15` | `INTERNAL_GAP` | `NOT_READY` | 런타임 3개 모델별 모델 카드와 승인 결과가 없고 통합 설명만 존재한다. | 모델별 카드 3개를 작성하고 provenance·제약·승인 결과를 각각 고정한다. |
| `DLV-AIML-16` | `INTERNAL_GAP` | `NOT_READY` | 통합 모델 해시는 결속됐지만 두 fallback 모델의 원천 provenance와 변환 동등성 증거가 없다. | fallback 원천 파일과 변환 체인을 복구하고 동등성 평가를 실행한다. |
| `DLV-AIML-17` | `INTERNAL_GAP` | `NOT_READY` | 학습 전 동결된 protocol/split hash/탐색 grid/수용 기준/tolerance가 없다. | 재학습 전 실험 프로토콜과 분할·그리드·판정 허용치를 선동결한다. |
| `DLV-AIML-23` | `INTERNAL_GAP` | `NOT_READY` | threshold sweep와 독립 재평가가 NOT_RUN이며 curve·비용·승인 결과가 없다. | 고정 데이터에서 sweep와 독립 재평가를 실행하고 곡선·비용·승인 판정을 남긴다. |

## 공통 AIML 생성 소스 결속

- 생성기 실행 결과: `PASS`, 검증 파일 `11`개
- 생성기 `--check`: `PASS`, 검증 파일 `11`개
- 단위 테스트: `19 collected / 19 passed / 0 failed`
- 증거 형태: 세션 내 해시 결속 증거이며 독립 실행 로그 파일은 없다.
- 형식 실행 증거: `0`; 5개 실행 게이트는 `NOT_RUN`; 릴리스는 `NOT_ELIGIBLE`.

| 변경 파일 | SHA-256 | bytes |
|---|---|---:|
| `scripts/build_walksafe_formal_aiml_20260721.py` | `5babd4df6d56db99c1222611b4659c3cfa82d717beb773b7d854b8a87d376f15` | 84116 |
| `tests/test_walksafe_formal_aiml.py` | `b5fedb010da952c9dbc5a0b82d505b425cbd804305224355637219516f5f8551` | 23351 |
| `docs/deliverables/08-ai-ml-data/data-management.md` | `34426ab6b8d4ec8410291f750a5049e8f16109e84e3d07eb78e9c60be0f0e559` | 39378 |
| `docs/deliverables/08-ai-ml-data/model-development.md` | `19239979cdcba6e52a6dea084c7f1fe8c5dfc5aae80d050a9c4c742604e8b939` | 23552 |
| `docs/deliverables/08-ai-ml-data/registers/dataset-register.json` | `c47ae48a0d52aa2fce119f6a8432cacf3aaf21f7ef063b76f19cfad0ad1983bd` | 26684 |
| `docs/deliverables/08-ai-ml-data/registers/model-register.json` | `3161e34f86ccef324a1864ad1c7310b2e31d7bcf5ed09e55945b5840635e8f88` | 12663 |
| `docs/deliverables/manifests/aiml-draft-20260721-r001.json` | `5b912ac448732ee9860cfc723bcd3d677f8d119dbd30bc2ff5c58f3e5fd47770` | 38456 |

## 런타임 모델 3종

- 런타임 구성: `apps/android/app/src/main/assets/model-config/two_model_runtime.json` / `756acb36b1af081ae5a3e484e4f07a158600c9707c058005969b98c91d9add73` / 5508 bytes
- 클래스 맵: `apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TwoModelClassMap.kt` / `0f7077a45304338df1ada4e589bd1325c103168418ddb41472af73d61220b83f` / 3567 bytes
- 정확한 모델 집합: `unified_walksafe`, `custom_tactile`, `coco_general`
- `custom_tactile`, `coco_general`의 원천 provenance는 추정하지 않고 `UNKNOWN_PROVENANCE`로 유지한다.

| 모델 | 원천 provenance | Android asset | 입력 | 출력 | 역할 / fallback | 상태 |
|---|---|---|---|---|---|---|
| `unified_walksafe` | `CANDIDATE_SOURCE_ARTIFACT_BOUND_NOT_APPROVED` | `apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite` / `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` / 9984493 bytes | `DECLARED; shape=[1, 768, 768, 3]; dtype=float32; side=768` | `DECLARED; shape=[1, 300, 6]; dtype=float32` | `PRIMARY` / `{"alias":"legacy_two_model","runtime_model_ids":["custom_tactile","coco_general"]}` | `evaluation=NOT_RUN; equivalence=NOT_RUN; device=NOT_RUN; approval=NOT_APPROVED; release=NOT_ELIGIBLE` |
| `custom_tactile` | `UNKNOWN_PROVENANCE` | `apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite` / `3336a4411eda461a3159cca80c046d52a5dadfd6a91f3831490bb5574a160780` / 38460640 bytes | `PARTIAL_INPUT_SIZE_ONLY; shape=None; dtype=None; side=960` | `CLASS_ORDER_ONLY_TENSOR_CONTRACT_NOT_DECLARED; shape=None; dtype=None` | `LEGACY_TWO_MODEL_TACTILE_FALLBACK_COMPONENT` / `{"alias":"legacy_two_model","fallback_for":"unified_walksafe"}` | `evaluation=NOT_RUN; equivalence=NOT_RUN; device=NOT_RUN; approval=NOT_APPROVED; release=NOT_ELIGIBLE` |
| `coco_general` | `UNKNOWN_PROVENANCE` | `apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite` / `776cafdaf1e0bc585a076d4ee3dd71d62f653e0bc2f8504f287e7b5a76c689e1` / 10343256 bytes | `PARTIAL_INPUT_SIZE_ONLY; shape=None; dtype=None; side=640` | `CLASS_ORDER_ONLY_TENSOR_CONTRACT_NOT_DECLARED; shape=None; dtype=None` | `LEGACY_TWO_MODEL_GENERAL_FALLBACK_COMPONENT` / `{"alias":"legacy_two_model","fallback_for":"unified_walksafe"}` | `evaluation=NOT_RUN; equivalence=NOT_RUN; device=NOT_RUN; approval=NOT_APPROVED; release=NOT_ELIGIBLE` |

### 원천 자산

- `unified_walksafe`: `model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt` / `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` / 5411845 bytes / `PYTORCH`
- `custom_tactile`: path/hash/size/format 모두 `null`; 선언된 런타임 자산 참조만 존재한다.
- `coco_general`: path/hash/size/format 모두 `null`; 선언된 런타임 자산 참조만 존재한다.

### 클래스 계약

- `unified_walksafe`: count `13`, namespace `walksafe.unified_walksafe.class_id`, order SHA-256 `5a01ce89ef0123c136fc17910c326fc64e70a3eaed77f3d4e80548518722d6d3`
  - ordered labels: `person`, `bicycle`, `car`, `motorcycle`, `bus`, `truck`, `traffic light`, `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`, `e_scooter_obstruction`
- `custom_tactile`: count `3`, namespace `walksafe.custom_tactile.class_id`, order SHA-256 `85210b06510e5f0967d1c3ae325aa82e0731a11fe53e0b6a83f67e3d1ad84c00`
  - ordered labels: `normal_tactile_block`, `damaged_tactile_block`, `tactile_damage_area`
- `coco_general`: count `80`, namespace `coco.coco_general.class_id`, order SHA-256 `a2bb9c8218affdef450cc85951e6a5abc5e4d956cd37b1be3d04840ae0120c8e`
  - ordered labels: `person`, `bicycle`, `car`, `motorcycle`, `airplane`, `bus`, `train`, `truck`, `boat`, `traffic light`, `fire hydrant`, `stop sign`, `parking meter`, `bench`, `bird`, `cat`, `dog`, `horse`, `sheep`, `cow`, `elephant`, `bear`, `zebra`, `giraffe`, `backpack`, `umbrella`, `handbag`, `tie`, `suitcase`, `frisbee`, `skis`, `snowboard`, `sports ball`, `kite`, `baseball bat`, `baseball glove`, `skateboard`, `surfboard`, `tennis racket`, `bottle`, `wine glass`, `cup`, `fork`, `knife`, `spoon`, `bowl`, `banana`, `apple`, `sandwich`, `orange`, `broccoli`, `carrot`, `hot dog`, `pizza`, `donut`, `cake`, `chair`, `couch`, `potted plant`, `bed`, `dining table`, `toilet`, `tv`, `laptop`, `mouse`, `remote`, `keyboard`, `cell phone`, `microwave`, `oven`, `toaster`, `sink`, `refrigerator`, `book`, `clock`, `vase`, `scissors`, `teddy bear`, `hair drier`, `toothbrush`

## 형식 검증 및 릴리스 경계

| 항목 | 상태 |
|---|---|
| `model_evaluation` | `NOT_RUN` |
| `conversion_equivalence` | `NOT_RUN` |
| `on_device_validation` | `NOT_RUN` |
| `threshold_sweep` | `NOT_RUN` |
| `independent_reevaluation` | `NOT_RUN` |
| `release_approval` | `NOT_APPROVED` |
| `release_eligibility` | `NOT_ELIGIBLE` |

AIML 모델 등록부와 실제 런타임 자산의 정적 결속은 평가 성공, 변환 동등성, 실기기 성능, 임계값 승인 또는 배포 승인을 대신하지 않는다.

## Semantic projection 재현 계약

- projection root 순서: `artifact_dispositions`, `runtime_models`, `formal_boundary`
- `artifact_dispositions` exact fields: `artifact_type_id, disposition, internal_completion_candidate, independent_review_status, release_approval, release_eligibility`
- `runtime_models` exact fields: `runtime_model_id, source_provenance, source_artifact, android_asset, input_contract, output_contract, class_contract, runtime_role, fallback_contract, evaluation_status, conversion_equivalence_status, device_validation_status, deployment_eligible, release_eligibility`
- `formal_boundary` exact fields: `model_evaluation, conversion_equivalence, on_device_validation, threshold_sweep, independent_reevaluation, release_approval, release_eligibility`
- 배열 순서: artifact는 `scope.artifact_type_ids`, model은 `scope.runtime_model_ids`의 원본 배열 순서를 보존한다. shape, ordered labels, fallback model ID 등 중첩 배열도 원본 순서를 보존한다.
- 누락 필드: 오류로 처리하며 생략하거나 합성하지 않는다.
- 값 처리: `null`은 JSON `null`, boolean은 JSON `true`/`false`, string은 형변환·Unicode 정규화 없이 원문을 유지하고 필요한 JSON escape만 적용한다. 숫자도 문자열로 바꾸지 않는다.
- canonical JSON: UTF-8, `ensure_ascii=false`, object key 사전식 오름차순(`sort_keys=true`), 구분자 `,`와 `:`, 불필요한 공백 없음, 마지막 LF 1개.
- 해시: canonical JSON 바이트에 SHA-256을 적용하고 lowercase hexadecimal로 기록한다.
- input set: JSON의 `source_bindings` 전체를 같은 canonical JSON 규약으로 해시한다.
- content: JSON의 `integrity.content_fingerprint.value`만 `null`로 둔 문서 전체를 같은 canonical JSON 규약으로 해시한다.
- Markdown projection: 이 무결성 절 직전까지의 UTF-8 Markdown 원문 바이트를 그대로 SHA-256 해시한다.

## 무결성 핑거프린트

- JSON content: `55f5112644c0f8fe26cb1719c31f9242d70c629d602a9aa8531d256dfa177517`
- semantic projection: `557ce0fcf0a45cf06a9d9964ecfecb11aeb4e1e291971ba551eb378228f1916d`
- input set: `3d7769079742311200b547e5c9bc65529c64c9080b8f477498d8d1b22afc0141`
- Markdown projection: `ab1807c56d722118c865c442619c20c70821ca68f53fac649ebc7e93095293f1`

Markdown projection 핑거프린트는 이 무결성 절을 붙이기 전 본문 바이트를 해시한 값이다.
