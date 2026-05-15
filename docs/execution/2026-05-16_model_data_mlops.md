# 2026-05-16 Model/Data/MLOps catch-up

범위: 2026-05-15 계획과 2026-05-16 catch-up note 중 Vision Model/Data/MLOps lane의 미완료 항목만 수행했다. 새 학습, 대형 validation dataset 생성, Git commit/push는 하지 않았다.

## 확인한 기준 문서

- `plans/catchup/2026-05-16.md`
- `plans/daily/2026-05-15.md`
- `plans/.work/2026-05-16/catchup/vision-model-data-mlops.md`
- `daylog/2026-05-15.md`
- `README.md`
- `docs/model_training_status.md`
- `docs/model_training_handoff.md`
- `docs/execution/2026-05-15_model_validation.md`
- `docs/execution/2026-05-15_model_onnx_followup.md`
- `docs/execution/2026-05-15_runtime_followup_after_reset.md`
- `docs/execution/2026-05-15_pwa_server_detection_e2e.md`

저장소 루트 `AGENTS.md` 파일은 없었다. 적용 지침은 사용자 메시지의 AGENTS 지침이다.

## Handoff 표

| 항목 | 값 |
| --- | --- |
| primary backend artifact | `/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` |
| ONNX export artifact | `/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx` |
| `MODEL_VERSION` | `walksafe-kr-tactile-v2-full-20260514-best-02a6be87` |
| `MODEL_CLASS_ORDER` | `damaged_tactile_block,parked_kickboard_bicycle,construction_obstacle,pothole` |
| 실제 학습/검증 coverage | class `0 damaged_tactile_block` 중심 baseline |
| backend adapter 상태 | 현재 `backend/app/detector.py`는 `.pt`만 ready adapter로 지원. `.onnx`는 export 산출물이지만 backend ready artifact는 아님 |
| `MODEL_CONFIDENCE_THRESHOLD` 후보 | backend 기본값 `0.35`. failure sampling은 후보 수집을 위해 `0.25` 사용 |
| `MODEL_IOU_THRESHOLD` | `0.7` |
| `MODEL_IMAGE_SIZE` | `640` |
| `best.pt` sha256 | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |
| `best.onnx` sha256 | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |

권장 backend env:

```text
MODEL_ARTIFACT_PATH=/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
MODEL_VERSION=walksafe-kr-tactile-v2-full-20260514-best-02a6be87
MODEL_CLASS_ORDER=damaged_tactile_block,parked_kickboard_bicycle,construction_obstacle,pothole
MODEL_CONFIDENCE_THRESHOLD=0.35
MODEL_IOU_THRESHOLD=0.7
MODEL_IMAGE_SIZE=640
```

판정 문구: v2는 `damaged_tactile_block` baseline으로 유지할 수 있지만, 킥보드/자전거, 공사 장애물, 포트홀까지 포함한 4-class 서비스 성능 근거로 쓰면 안 된다.

## Metric / export 제한

| scope | precision | recall | mAP50 | mAP50-95 | 상태 |
| --- | ---: | ---: | ---: | ---: | --- |
| v2 validation final | `0.73656` | `0.58380` | `0.66394` | `0.49194` | 기존 학습 로그 기준 |
| v2 test split | `0.728` | `0.581` | `0.657` | `0.481` | 기존 2026-05-14/15 문서 기준 |
| VL2+VS2 external tactile subset | `0.751` | `0.605` | `0.680` | `0.501` | class `0` tactile subset 전용 |
| PT/ONNX raw tensor smoke | n/a | n/a | n/a | n/a | `max_abs=0.0010375977`, `allclose rtol=1e-3 atol=1e-3` 통과 |

ONNX full metric equivalence는 실행하지 않았다. 현재 완료 근거는 export, checker, raw tensor smoke, 30장 CPU latency smoke까지다. test split mAP/precision/recall을 PT와 ONNX로 직접 비교한 결과는 없다.

## Failure bucket 기준

| bucket | 기준 | 다음 action |
| --- | --- | --- |
| `missed_defect` | GT class `0`가 있으나 같은 class prediction의 최대 IoU가 `0.5` 미만이고, 작은 물체/저조도 추정 조건에 걸리지 않음 | label 품질을 확인하고 유사 positive를 v3 보강 후보로 추가 |
| `false_positive_normal_tactile` | label file이 비어 있는 negative image에서 class `0` prediction 발생 | hard negative로 추가하고 threshold/후처리 점검 |
| `low_light_or_blur` | missed GT 중 평균 휘도 `<70` 또는 Laplacian variance `<60` | capture 품질 가드, 저조도/흔들림 augmentation 후보 |
| `small_or_far` | missed GT 중 normalized GT area `<0.015` | 가까운 거리/다중 scale 샘플 보강, minimum box 정책 검토 |
| `new_class_gap` | class `1..3` 한국 GT가 없거나 부족해 평가/학습 근거 없음 | v4용 한국 킥보드/자전거, 공사물, 포트홀 데이터 수집/라벨링 |

## Failure 후보 샘플링

전체 test split inference를 먼저 시도했으나 약 1분 뒤 exit code `137`로 kill되어 완료하지 못했다. 이후 같은 기준에서 메모리 부담을 줄여 seed 고정 subset을 실행했다.

실행 기준:

```text
model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
dataset=datasets/walksafe_kr_v2
split=test subset
seed=20260516
sample=positive 180 + negative 180 images
conf=0.25
nms_iou=0.7
match_iou=0.5
imgsz=640
device=cpu
```

산출물:

- `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/failure_candidates.csv`
- `runs/failure_sampling/walksafe_kr_v2_test_subset_20260516/summary.json`

요약:

| 항목 | 값 |
| --- | ---: |
| full test images | `2,347` |
| full test positive / negative | `1,191 / 1,156` |
| sampled images | `360` |
| sampled GT boxes | `594` |
| matched GT boxes | `357` |
| missed GT boxes | `237` |
| negative images with prediction | `22` |
| candidate rows total | `264` |
| selected review rows | `40` |

선정된 40행 bucket:

| bucket | rows |
| --- | ---: |
| `false_positive_normal_tactile` | `16` |
| `missed_defect` | `12` |
| `small_or_far` | `12` |

이번 subset에서는 `low_light_or_blur` 후보가 threshold에 걸리지 않았고, `new_class_gap`은 `walksafe_kr_v2` test split에 class `1..3` GT가 없어 CSV 샘플로 생성하지 않았다.

## VL1+VS1 hard-negative 계획

실행한 dry-run:

```bash
python3 data_sources/scripts/build_aihub513_validation_subset.py \
  --pair 1 \
  --download-root "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation" \
  --include-negatives \
  --negative-limit 10 \
  --dry-run \
  --quiet
```

결과:

| 항목 | 값 |
| --- | ---: |
| tactile unique filenames | `1,038` |
| positive images | `0` |
| negative images | `1,038` |
| boxes | `0` |
| missing tactile images | `0` |
| selected negative sample | `10` |
| estimated selected image bytes | `54,642,136` |

판정: VL1+VS1은 damaged tactile 성능 검증에는 부적합하고, 정상 점자블록 false positive hard-negative 평가용으로만 사용한다.

권장 실행 방식:

1. `negative-limit 200`으로 ignored `runs/validation/aihub513_vl1_vs1_tactile_hard_negative_200` subset 생성.
2. v2 `best.pt`를 `conf=0.25/0.35`, `iou=0.7`, `imgsz=640` 두 threshold로 실행.
3. false positive image count, detection count, max confidence p50/p95, 상위 30개 이미지 CSV를 기록.
4. 공간 여유와 시간이 확보되면 full 1,038장으로 확대한다.

## v3 / v4 backlog

| 버전 후보 | 초점 | 입력 후보 | 완료 기준 |
| --- | --- | --- | --- |
| v3 | class `0` 품질 보강 | 이번 failure CSV의 missed/false-positive/small-or-far 후보, VL1+VS1 hard negative | class `0` recall 개선과 false positive 감소를 test/external subset으로 확인 |
| v4 | class `1..3` 한국 데이터 보강 | 한국 보행환경 킥보드/자전거, 공사 구조물/적치물, 포트홀 이미지/영상 | 4-class train/val/test GT 확보 후 class별 metric 산출 |

## 검증

- `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`: `/` 가용 `18G`, 사용률 `98%`.
- `sha256sum runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`: 위 hash 확인.
- `.venv/bin/python` package 확인: `ultralytics 8.4.48`, `torch 2.11.0+cu130`, `onnxruntime 1.26.0`, `cv2 4.13.0`.
- `walksafe_kr_v2` test subset failure sampling: PASS, CSV 40행 생성.
- VL1+VS1 hard-negative dry-run: PASS, positive `0`, negative `1,038` 확인.

## 미완료 / 제한

- full test split failure sampling은 exit code `137`로 중단되어 완료하지 못했다.
- ONNX full metric equivalence는 실행하지 않았다.
- browser/ONNX Runtime Web latency는 실행하지 않았다.
- class `1..3` 한국 GT가 없어 4-class metric이나 `new_class_gap` 실제 샘플 CSV는 생성하지 못했다.
- 생성된 CSV는 자동 후보이며 수동 시각 검수와 개인정보/위치정보 비식별 검토가 필요하다.
