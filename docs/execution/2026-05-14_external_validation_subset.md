# AI Hub 513 external validation subset

작성일: 2026-05-14 KST

## 범위

VL/VS validation zip pair를 직접 받는 subset converter를 추가하고, 로컬 v2 `best.pt`로 VL2+VS2 tactile subset 외부 검증을 수행했다. 원본 zip 전체 압축 해제, 데이터셋/이미지/라벨/run/`.pt` Git 업로드, commit/push는 수행하지 않았다.

## 추가한 스크립트

`data_sources/scripts/build_aihub513_validation_subset.py`

지원 옵션:

- `--pair 1|2`
- `--download-root`
- `--label-zip`, `--image-zip`
- `--output-dir` / `--target`
- `--positive-limit`, `--negative-limit`, `--include-negatives`
- `--seed`
- `--dry-run`
- `--overwrite`
- `--json-summary`

기본 output은 `runs/validation/aihub513_vl*_vs*_tactile_subset` 아래라서 현재 `.gitignore`의 `runs/` 규칙으로 무시된다.

## 파싱 규칙

기존 `build_walksafe_kr_tactile.py`와 같은 규칙을 사용한다.

- tactile file: label member path에 `점자블럭`이 있거나 `description.facility == "2_09"`
- tactile annotation: `annotation.label_name == "점자블럭"`
- positive box: `annotation.is_defect`가 `불량`으로 시작하고 bbox/polygon이 image bounds clipping 후 유효한 경우
- YOLO label: class `0 damaged_tactile_block`만 기록

`data.yaml`은 v2 모델과 맞추기 위해 4-class contract를 유지하지만, 이 subset의 label row는 class `0`만 사용한다.

## 검증 명령

구문/도움말:

```bash
python3 -m py_compile data_sources/scripts/build_aihub513_validation_subset.py
python3 data_sources/scripts/build_aihub513_validation_subset.py --help
```

dry-run:

```bash
python3 data_sources/scripts/build_aihub513_validation_subset.py \
  --pair 1 \
  --download-root "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation" \
  --include-negatives \
  --negative-limit 10 \
  --dry-run \
  --quiet

python3 data_sources/scripts/build_aihub513_validation_subset.py \
  --pair 2 \
  --label-zip "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/라벨링데이터/VL2.zip" \
  --image-zip "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS2.zip" \
  --dry-run \
  --quiet
```

small build smoke:

```bash
python3 data_sources/scripts/build_aihub513_validation_subset.py \
  --pair 2 \
  --download-root "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation" \
  --output-dir runs/validation/aihub513_vl2_vs2_tactile_smoke \
  --positive-limit 5 \
  --include-negatives \
  --negative-limit 5 \
  --overwrite \
  --quiet

python3 model/validate_yolo_dataset.py \
  --data runs/validation/aihub513_vl2_vs2_tactile_smoke/data.yaml
```

VL2+VS2 full tactile subset build:

```bash
python3 data_sources/scripts/build_aihub513_validation_subset.py \
  --pair 2 \
  --download-root "/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation" \
  --output-dir runs/validation/aihub513_vl2_vs2_tactile_subset \
  --include-negatives \
  --quiet

python3 model/validate_yolo_dataset.py \
  --data runs/validation/aihub513_vl2_vs2_tactile_subset/data.yaml
```

Ultralytics validation:

```bash
.venv/bin/yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data=runs/validation/aihub513_vl2_vs2_tactile_subset/data.yaml \
  split=val \
  imgsz=640 \
  batch=16 \
  device=0 \
  plots=true \
  save_json=true \
  project=runs/detect \
  name=walksafe_kr_tactile_v2_aihub513_vl2_vs2_tactile_subset \
  > runs/validation/aihub513_vl2_vs2_tactile_subset_val.log 2>&1
```

## Dry-run 결과

| pair | tactile images | positives | negatives | boxes | missing tactile images | 해석 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| VL1+VS1 | 1,038 | 0 | 1,038 | 0 | 0 | negative/hard-negative check only |
| VL2+VS2 | 2,082 | 1,578 | 504 | 5,326 | 0 | primary damaged tactile external validation subset |

## 생성한 로컬 산출물

| path | 내용 |
| --- | --- |
| `runs/validation/aihub513_vl2_vs2_tactile_smoke` | smoke dataset, 10 images / 10 labels |
| `runs/validation/aihub513_vl2_vs2_tactile_subset` | external validation dataset, 2,082 images / 2,082 labels |
| `runs/validation/aihub513_vl2_vs2_tactile_subset_val.log` | Ultralytics validation log |
| `runs/detect/runs/detect/walksafe_kr_tactile_v2_aihub513_vl2_vs2_tactile_subset` | validation plots and `predictions.json` |

Full subset build counts:

| item | count |
| --- | ---: |
| images written | 2,082 |
| positive labels | 1,578 |
| negative labels | 504 |
| label rows written | 5,326 |
| selected source image bytes | 10,567,982,687 |

Local validator result:

- train: 0 images / 0 labels
- val: 2,082 images / 2,082 labels
- test: 0 images / 0 labels
- result: valid, with expected empty train/test warnings

## External validation metric

Model:

`runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`

Dataset:

`runs/validation/aihub513_vl2_vs2_tactile_subset/data.yaml`

Ultralytics result:

| class | images | instances | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| all | 2,082 | 5,325 | 0.751 | 0.605 | 0.680 | 0.501 |
| damaged_tactile_block | 1,578 | 5,325 | 0.751 | 0.605 | 0.680 | 0.501 |

Speed:

```text
0.3ms preprocess, 0.6ms inference, 0.0ms loss, 0.3ms postprocess per image
```

## 주의 및 제한

- 이 검증은 tactile-block damage class `0` 전용이다. v2 `data.yaml`의 4-class contract는 유지하지만 class `1..3` ground truth는 없다.
- VL1+VS1은 positive box가 0개라 damaged tactile 성능 검증에는 사용할 수 없고 hard-negative check로만 의미가 있다.
- converter는 VS zip central directory를 읽고 선택된 image member만 추출한다. 전체 VS zip은 압축 해제하지 않았다.
- 선택 이미지는 zip member에서 직접 추출되므로 filesystem source file이 없어 hard link를 만들 수 없다.
- Ultralytics가 extracted subset 안의 JPEG 1,637개를 `corrupt JPEG restored and saved`로 복구 저장했다. 원본 zip은 수정되지 않았고, subset은 ignored `runs/` 아래에만 있다.
- converter가 쓴 label row는 5,326개지만 Ultralytics validation instances는 5,325개다. 한 label file에 exact duplicate row 1개가 있어 Ultralytics가 중복을 제외한 것으로 보인다.
- validation 후 `runs/validation/aihub513_vl2_vs2_tactile_subset` 용량은 약 18G이고 `/` 여유 공간은 약 6.6G였다. 추가 대형 subset/eval 전에는 cleanup이 필요하다.
