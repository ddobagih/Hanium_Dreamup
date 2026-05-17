# 2026-05-17 Model/Data/MLOps catch-up

범위: Vision Model/Data/MLOps lane의 2026-05-17 r1 미완료 항목만 수행했다. 새 학습, Git commit/push, daylog 작성은 하지 않았다.

## 기준 문서

- `plans/catchup/2026-05-17.md`
- `plans/daily/2026-05-16.md`
- `plans/daily/2026-05-17.md`
- `plans/.work/2026-05-17/catchup/vision-model-data-mlops.md`
- `daylog/2026-05-16.md`
- `README.md`
- `model/README.md`
- `data_sources/README.md`
- `datasets/walksafe_kr_v1/README.md`
- `datasets/walksafe_v1/README.md`
- `docs/execution/2026-05-16_model_data_mlops.md`

저장소 내부 `AGENTS.md`는 없었고 사용자 제공 지침을 적용했다.

## 산출물 / 디스크 / Git 안전

| 항목 | 결과 |
| --- | --- |
| 디스크 | 실행 전 `/` 가용 `18G`, 실행 후 `17G`, 사용률 `99%` |
| `best.pt` sha256 | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |
| `best.onnx` sha256 | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |
| Git 대형 산출물 추적 | `.pt`, `.onnx`, `runs/**`, `datasets/walksafe_kr_v2/**` 추적 없음. `.gitkeep`만 확인 |

## ONNX metric equivalence

같은 `datasets/walksafe_kr_v2` test split 전체 `2,347`장, CPU, `imgsz=640` 기준으로 PT와 ONNX를 각각 `yolo detect val`로 실행했다.

| model | precision | recall | mAP50 | mAP50-95 | elapsed | max RSS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `best.pt` | `0.729` | `0.581` | `0.657` | `0.481` | `2:48.01` | `1,354,256 KB` |
| `best.onnx` | `0.731` | `0.580` | `0.655` | `0.482` | `3:58.74` | `952,212 KB` |

Delta:

| metric | ONNX - PT |
| --- | ---: |
| precision | `+0.002` |
| recall | `-0.001` |
| mAP50 | `-0.002` |
| mAP50-95 | `+0.001` |

판정: 계획 기준인 mAP50-95 절대 차이 `0.01` 이내, precision/recall 절대 차이 `0.02` 이내를 만족한다.

## PT/ONNX latency

`datasets/walksafe_kr_v2/images/test`의 정렬 기준 첫 120장으로 측정했다. warmup 5장은 제외했고, 디스크 read는 제외했다. Ultralytics preprocess, inference, postprocess는 포함했다.

산출물: `runs/benchmark/walksafe_kr_v2_test_latency_20260517/summary.json`

| model | mean ms | p50 ms | p95 ms | min ms | max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| PT | `10.1030` | `9.4369` | `12.6493` | `8.4989` | `15.0246` |
| ONNX Runtime CPU | `36.4025` | `35.6895` | `58.1289` | `21.7789` | `62.0352` |

판정: ONNX CPU p95는 로컬 서버 후보로는 충분히 낮지만 PT 대비 p95 ratio가 `4.5954`라 속도상 우위는 없다. 현재 backend ready artifact는 계속 `.pt` 기준으로 두는 것이 맞다. browser/ONNX Runtime Web latency는 실행하지 않았다.

## VL1+VS1 hard-negative

VL1+VS1은 positive box가 0개라 recall/mAP 평가가 아니라 정상 점자블록 false positive 평가로만 사용했다.

생성 subset:

- `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517`
- selected images: `200`
- positive selected: `0`
- negative selected: `200`
- image bytes written: `1,051,173,158`

실제 추론 산출물:

- `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517_inference/summary.json`
- `top_false_positive_candidates_conf_025.csv`
- `top_false_positive_candidates_conf_035.csv`
- `per_image_conf_025.csv`
- `per_image_conf_035.csv`

| conf | images | FP image count | FP detections | FP max conf p50 | FP max conf p95 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| `0.25` | `200` | `32` | `52` | `0.515457` | `0.902571` |
| `0.35` | `200` | `21` | `30` | `0.591713` | `0.922201` |

판정: `0.35`에서도 정상 점자블록을 damaged로 보는 고신뢰 FP가 남아 있어 v3 hard-negative 보강 후보로 유지한다. 전체 `1,038`장 확장은 약 5GB 이상 추가 복사가 필요하고, 200장 결과에서 이미 명확한 FP 후보가 나와 이번 r1에서는 보류했다.

## Failure CSV 수동 검수

기존 자동 후보 `40`행을 contact sheet로 확인했다.

산출물:

- `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/manual_review_20260517.csv`
- `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/manual_review_20260517_summary.json`
- `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/review_sheets_20260517/failure_review_sheet_1.jpg` ~ `4.jpg`

| 수동 판정 | rows | v3 action |
| --- | ---: | --- |
| `normal_tactile_block_false_positive` | `16` | `keep_as_hard_negative` |
| `true_damage_localization_error` | `12` | `keep_positive_and_improve_box_localization` |
| `tiny_or_low_salience_defect_review_min_box_policy` | `9` | `review_min_box_policy_before_training` |
| `visible_small_defect_positive` | `3` | `keep_positive_with_small_object_augmentation` |

개인정보/위치정보: contact sheet에서 명확한 얼굴/차량번호는 보이지 않았지만, 원본 이미지는 계속 local-only로 두고 학습/공유 전 source-level privacy/location 검토가 필요하다.

## v3 / v4 판단

- v3: class `0 damaged_tactile_block` 품질 보강으로 한정한다. 입력 후보는 수동 검수된 hard-negative `16`, localization 보정 positive `12`, small-object 후보 `3`, 최소 박스 정책 재검토 `9`다.
- v4: class `1..3` 한국 GT가 없어 4-class metric은 만들지 않았다. 킥보드/자전거, 공사 구조물/적치물, 포트홀은 별도 한국 데이터 수집/라벨링 후 class별 metric을 산출해야 한다.
- handoff 문구는 유지한다. v2는 class `0` 점자블록 baseline이며 4-class 서비스 성능 근거가 아니다. 현재 backend ready artifact는 `.pt`다.

## 검증

- `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`
- `sha256sum runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`
- `git ls-files '*.pt' '*.pth' '*.onnx' '*.engine' '*.tflite' 'runs/**' 'datasets/walksafe_kr_v2/**' 'datasets/**/images/**' 'datasets/**/labels/**'`
- `.venv/bin/yolo detect val` for PT and ONNX test split
- Python latency benchmark over 120 decoded test images
- `data_sources/scripts/build_aihub513_validation_subset.py` VL1+VS1 200 negative subset build
- `model/validate_yolo_dataset.py --data runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200_20260517/data.yaml`: 구조 valid, train/test 비어 있음 warning은 hard-negative val-only subset 특성
- Python hard-negative inference summary at `conf=0.25` and `0.35`
- Contact-sheet assisted review of 40 failure rows

## 미완료 / 제한

- browser/ONNX Runtime Web latency는 실행하지 않았다.
- VL1+VS1 전체 1,038장 hard-negative inference는 이번 r1에서 보류했다.
- full test split failure sampling 재시도는 하지 않았다. 기존 exit code `137` 기록이 있어 streaming/저장량 제한 방식으로 별도 수행해야 한다.
- class `1..3` 한국 GT 부재로 4-class metric은 산출하지 않았다.
- contact sheet 검수는 빠른 시각 triage이며 개인정보/위치정보 정식 비식별 검수는 아니다.
