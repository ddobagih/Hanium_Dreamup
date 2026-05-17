# 2026-05-18 Model/Data/MLOps catch-up

범위: Vision Model/Data/MLOps lane의 2026-05-18 r1 미완료 항목만 수행했다. 새 학습, 대형 inference, Git commit/push, daylog 작성은 하지 않았다.

## 기준 문서

- `plans/catchup/2026-05-18.md`
- `plans/daily/2026-05-17.md`
- `plans/.work/2026-05-18/catchup/vision-model-data-mlops.md`
- `daylog/2026-05-17.md`
- `docs/execution/2026-05-17_model_data_mlops.md`
- `README.md`
- `model/README.md`
- `data_sources/README.md`

저장소 내부 `AGENTS.md`는 없었고 사용자 제공 지침을 적용했다.

## Gate 확인

| 항목 | 결과 |
| --- | --- |
| 디스크 | `/home/ddobagi/Code/hanium-dreamup` 가용 `17G`, 사용률 `99%` |
| `best.pt` sha256 | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |
| `best.onnx` sha256 | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |
| Git 대형 산출물 추적 | `.pt`, `.onnx`, `runs/**`, `datasets/walksafe_kr_v2/**`, dataset images/labels 추적 없음. `.gitkeep`만 확인 |

## v3 curation manifest

기존 2026-05-17 failure 후보 40행 수동 triage 결과를 v3 후보 manifest로 정리했다.

산출물:

- `data_sources/manifests/walksafe_kr_v3_curation_manifest_2026-05-18.csv`
- `data_sources/manifests/walksafe_kr_v3_curation_summary_2026-05-18.json`

| action | rows |
| --- | ---: |
| `keep_as_hard_negative` | 16 |
| `keep_positive_and_improve_box_localization` | 12 |
| `review_min_box_policy_before_training` | 9 |
| `keep_positive_with_small_object_augmentation` | 3 |

Manifest는 `source`, `bucket`, `action`, `privacy_status`, `split_policy`를 포함한다. 원본 이미지는 계속 local-only이며 source-level privacy/location audit 전에는 공유하거나 공개 학습 근거로 쓰지 않는다.

## VL1+VS1 hard-negative FP 검수

conf `0.35` 상위 FP 후보 30개 detection을 21개 unique image로 묶고 contact sheet를 만들어 시각 검수했다.

로컬 검수 sheet:

- `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517_inference/review_sheets_20260518/fp_conf035_review_sheet_1.jpg`
- `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517_inference/review_sheets_20260518/fp_conf035_review_sheet_2.jpg`
- `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517_inference/review_sheets_20260518/fp_conf035_review_sheet_3.jpg`

추적 manifest:

- `data_sources/manifests/walksafe_kr_v3_hard_negative_fp_review_2026-05-18.csv`

판정:

- 21개 image 모두 정상 또는 마모/오염/그림자가 있는 점자블록을 damaged로 본 hard-negative 후보로 유지한다.
- 특히 강한 마모/그림자가 있는 4건은 threshold/augmentation 검토 우선 후보로 표시했다.
- review sheet에서 명확한 얼굴/차량번호는 보이지 않았다. 1건은 신발 일부가 보여 공유 전 privacy audit이 필요하다.

## full failure sampling 재시도 설계

기존 full test split failure sampling은 exit code `137` 기록이 있어 같은 방식의 대형 재실행은 하지 않았다. 다음 재시도는 아래 제약으로 수행한다.

| 항목 | 기준 |
| --- | --- |
| 입력 | `datasets/walksafe_kr_v2/images/test` 2,347장 |
| 실행 방식 | 이미지별 streaming inference, 결과 CSV append |
| 후보 제한 | bucket별 최대 50행, 전체 최대 200행 |
| 이미지 저장 | 기본 off, `--save-contact-sheets` 같은 명시 옵션에서만 sheet 생성 |
| checkpoint | 100장마다 processed image path와 row count 기록 |
| resume | checkpoint 이후 image부터 재개 |
| metrics | missed/low-IoU/FP/small-or-far bucket count, max confidence, IoU, GT/pred bbox |
| 중단 조건 | `/` 가용 10GB 미만, RSS 2GB 초과, CSV 10MB 초과 시 중단 |

목표는 전체 이미지를 복사하거나 prediction 이미지를 대량 저장하지 않고, CSV와 작은 contact sheet만 남기는 것이다.

## v4 데이터 계획 / stale 문서 보정

- `data_sources/manifests/korean_dataset_candidates.md`에 class `1..3` 한국 데이터 확보 계획을 추가했다.
- `docs/model_training_status.md`의 완료/미완료 검증 표현을 2026-05-18 기준으로 보정했다.
- `docs/model_integration_plan.md`의 ONNX full metric equivalence 미완료 문구를 보정했다. browser/ONNX Runtime Web latency는 여전히 미실행으로 남겼다.
- `docs/model_v2_status.md`에 VL1+VS1 hard-negative 결과와 v3 manifest 기반 다음 흐름을 반영했다.
- `docs/model_placeholder_systems.md`, `docs/frontend_handoff_without_model.md`의 “모델 미연결/어댑터 없음” 표현을 server `.pt` adapter 구현 상태에 맞게 보정했다.

## 검증

- `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`
- `sha256sum runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`
- `git ls-files '*.pt' '*.pth' '*.onnx' '*.engine' '*.tflite' 'runs/**' 'datasets/walksafe_kr_v2/**' 'datasets/**/images/**' 'datasets/**/labels/**'`
- CSV/JSON 생성: v3 manifest 40행, VL1+VS1 FP review 21행, summary JSON 생성 확인
- contact sheet 시각 검수: 3장 확인

## 미완료 / 제한

- browser/ONNX Runtime Web latency는 실행하지 않았다.
- VL1+VS1 전체 1,038장 hard-negative inference는 실행하지 않았다. 200장에서 이미 FP 후보가 확인됐고 `/` 사용률이 `99%`다.
- 새 학습과 full failure sampling 재실행은 하지 않았다.
- class `1..3` 한국 GT가 없어 4-class metric은 산출하지 않았다.
- contact sheet 검수는 빠른 시각 triage이며 개인정보/위치정보 정식 비식별 검수는 아니다.
