# AI Hub 513 External Validation Readiness

작성일: 2026-05-14 KST
작업 범위: 외부 validation 준비 점검만 수행. 새 학습, 대용량 다운로드, 삭제는 수행하지 않음.

## 결론

현재 로컬에는 AI Hub 513 공식 validation zip과 v2 기준선 모델이 모두 보인다. 그러나 full external validation을 지금 바로 안전하게 시작할 수는 없다.

막힘 조건은 두 가지다.

1. 디스크 여유 공간이 부족하다. `/` 가용 공간은 약 `25G`이고, `VS1.zip`, `VS2.zip`은 각각 `101G`다. 기존 변환 스크립트는 zip 내부 이미지를 target dataset으로 복사하므로 full validation dataset 생성에는 현재 여유 공간이 부족하다.
2. `VL*/VS*` 전용 validation 변환 스크립트가 없다. 기존 `data_sources/scripts/build_walksafe_kr_tactile.py`는 `TL8/TL9/TS8/TS9` 파일명을 하드코딩해서 찾는다. symlink로 우회할 수는 있지만, 공식 validation 전체를 별도 val split으로 만드는 전용 경로는 없다.

따라서 현재 상태는 `입력 파일 발견됨 / readiness dry-run 가능 / full external validation 즉시 실행 불가`로 판단한다.

## 확인한 로컬 상태

참고 문서:

- `docs/model_training_3day_execution_plan.md`
- `docs/model_v2_status.md`
- `docs/model_training_status.md`
- `model/README.md`

모델 스크립트:

- 요청에 언급된 `scripts/model` 디렉터리는 현재 저장소에 없다.
- 실제 모델 스크립트는 `model/` 아래에 있다.
- `model/train_yolo.py`: YOLO 학습 wrapper. 외부 validation에는 사용하지 않는다.
- `model/validate_yolo_dataset.py`: YOLO dataset 구조 검증. 표준 `images/{split}` + `labels/{split}` 구조 검증에는 사용 가능하다.
- `data_sources/scripts/build_walksafe_kr_tactile.py`: AI Hub 513 `TL8/TL9/TS8/TS9`를 YOLO dataset으로 변환한다.

v2 기준선:

- data yaml: `datasets/walksafe_kr_v2/data.yaml`
- model: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
- `best.pt` 크기: `5.2M`
- frozen hash manifest: `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt`
- v2 split count:
  - train: `16,433` images / `16,433` labels
  - val: `4,695` images / `4,695` labels
  - test: `2,347` images / `2,347` labels

AI Hub 513 validation zip:

```text
/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/라벨링데이터/VL1.zip  48M
/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/라벨링데이터/VL2.zip  61M
/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS1.zip  101G
/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS2.zip  101G
```

Python/YOLO 환경:

- system `python`: 없음
- system `python3`: 있음, 하지만 `ultralytics` 없음
- repo `.venv`: 있음
- `.venv/bin/yolo`: 있음
- `.venv`의 `ultralytics`: `8.4.48`

따라서 명령은 `python`이나 bare `yolo` 대신 `.venv/bin/python`, `.venv/bin/yolo`를 직접 쓰는 편이 안전하다.

## 변환 스크립트 판단

전용 external validation 변환 스크립트는 없다.

기존 `build_walksafe_kr_tactile.py`로 가능한 것:

- `TL8/TL9` 라벨 zip과 `TS8/TS9` 이미지 zip을 읽어 YOLO dataset을 생성한다.
- `--max-positive 0 --max-negative 0`이면 전체 sample을 사용한다.
- `--dry-run`으로 label/image 매칭과 missing image 수를 확인할 수 있다.
- target 아래 `images/{train,val,test}`, `labels/{train,val,test}`, `BUILD_SUMMARY.md`를 만든다.
- `data.yaml`은 만들지 않으므로 external validation target에는 별도로 작성해야 한다.

기존 스크립트 한계:

- `VL1/VL2/VS1/VS2` 파일명을 직접 받는 옵션이 없다.
- full validation zip을 쓰려면 `VL1 -> TL8`, `VL2 -> TL9`, `VS1 -> TS8`, `VS2 -> TS9` symlink 우회가 필요하다.
- 기본 동작은 target을 reset한다. 기존 데이터셋을 target으로 지정하면 파일 삭제가 발생할 수 있으므로 반드시 새 ignored target만 사용해야 한다.
- official validation 전체를 하나의 `val` split으로 직접 생성하는 옵션이 없다. 기본 split은 train/val/test random split이다.
- 모든 샘플을 `train`에 넣고 `data.yaml`의 `val: images/train`으로 alias하는 방식은 Ultralytics에는 가능하지만, 현재 `model/validate_yolo_dataset.py`는 `labels/val`을 고정으로 찾기 때문에 그대로는 구조 검증이 맞지 않는다.

## 최소 입력

full external validation 시작 전 필요한 최소 입력:

- v2 weight: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`
- class contract: `datasets/walksafe_kr_v2/data.yaml`의 4-class `names`
- AI Hub 513 validation label/image pair:
  - `VL1.zip` + `VS1.zip`
  - `VL2.zip` + `VS2.zip`
- 실행 환경:
  - `.venv/bin/python`
  - `.venv/bin/yolo`
- 충분한 target 공간:
  - 현재 `25G`는 부족하다.
  - 기존 복사형 변환 방식을 유지한다면 최소 수백 GB 단위 여유 공간을 확보한 뒤 시작해야 한다.

## 예상 산출 경로

변환 산출물:

```text
runs/validation/aihub513_val_links/
runs/validation/aihub513_official_val_dataset/
runs/validation/aihub513_official_val_dataset/BUILD_SUMMARY.md
runs/validation/aihub513_official_val_dataset/data.yaml  # 수동 생성 필요
```

validation 실행 산출물:

```text
runs/detect/walksafe_kr_tactile_v2_aihub513_official_val/
runs/detect/walksafe_kr_tactile_v2_aihub513_official_val/confusion_matrix.png
runs/detect/walksafe_kr_tactile_v2_aihub513_official_val/confusion_matrix_normalized.png
runs/detect/walksafe_kr_tactile_v2_aihub513_official_val/BoxPR_curve.png
runs/detect/walksafe_kr_tactile_v2_aihub513_official_val/predictions.json
runs/validation/aihub513_official_val_20260514.log
```

`runs/`는 `.gitignore` 대상이다.

## 안전한 명령

아래 명령은 full validation을 바로 시작하기 전 점검용이다. 대용량 다운로드나 학습은 하지 않는다.

```bash
cd /home/ddobagi/Code/hanium-dreamup

AIHUB513_ROOT="/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation"
LINK_ROOT="runs/validation/aihub513_val_links"
TARGET="runs/validation/aihub513_official_val_dataset"

test -f runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
test -f datasets/walksafe_kr_v2/data.yaml

find "$AIHUB513_ROOT" -name 'VL1.zip' -o -name 'VL2.zip' -o -name 'VS1.zip' -o -name 'VS2.zip'
df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads

.venv/bin/python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml
.venv/bin/yolo version
```

기존 변환 스크립트로 read-only dry-run을 해볼 때의 명령:

```bash
cd /home/ddobagi/Code/hanium-dreamup

AIHUB513_ROOT="/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation"
LINK_ROOT="runs/validation/aihub513_val_links"
TARGET="runs/validation/aihub513_official_val_dataset"

mkdir -p "$LINK_ROOT"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VL1.zip' -print -quit)" "$LINK_ROOT/TL8.zip"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VL2.zip' -print -quit)" "$LINK_ROOT/TL9.zip"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VS1.zip' -print -quit)" "$LINK_ROOT/TS8.zip"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VS2.zip' -print -quit)" "$LINK_ROOT/TS9.zip"

.venv/bin/python data_sources/scripts/build_walksafe_kr_tactile.py \
  --download-root "$LINK_ROOT" \
  --target "$TARGET" \
  --max-positive 0 \
  --max-negative 0 \
  --dry-run
```

full build는 현재 디스크 상태에서는 실행하지 않는다. 공간 확보 후에도 target이 새 경로인지 확인한 뒤 실행한다.

```bash
test ! -e "$TARGET"

.venv/bin/python data_sources/scripts/build_walksafe_kr_tactile.py \
  --download-root "$LINK_ROOT" \
  --target "$TARGET" \
  --max-positive 0 \
  --max-negative 0

cat > "$TARGET/data.yaml" <<YAML
path: $TARGET
train: images/train
val: images/val
test: images/test

names:
  0: damaged_tactile_block
  1: parked_kickboard_bicycle
  2: construction_obstacle
  3: pothole
YAML

.venv/bin/python model/validate_yolo_dataset.py --data "$TARGET/data.yaml"
```

생성된 표준 split 중 `val` split을 평가하는 명령:

```bash
.venv/bin/yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data="$TARGET/data.yaml" \
  split=val \
  imgsz=640 \
  batch=8 \
  plots=true \
  save_json=true \
  project=runs/detect \
  name=walksafe_kr_tactile_v2_aihub513_official_val \
  2>&1 | tee runs/validation/aihub513_official_val_20260514.log
```

주의: 위 full build 명령은 기존 스크립트의 기본 train/val/test split을 사용하므로 official validation 전체를 한 번에 평가하는 명령은 아니다. 전체 official validation을 하나의 val set으로 보려면 전용 converter를 추가하거나, all-in-one split 구조와 validator 동작을 맞추는 별도 작업이 필요하다.

## 시작 전 막힘 조건

다음 중 하나라도 해당하면 full external validation을 시작하지 않는다.

- `VL1.zip`, `VL2.zip`, `VS1.zip`, `VS2.zip` 중 하나라도 없다.
- label zip과 image zip 번호가 맞지 않는다.
- target으로 기존 `datasets/walksafe_kr_v2` 또는 기존 run 경로를 지정했다.
- `df -h` 기준 full target dataset을 만들 공간이 부족하다. 현재 `25G`는 부족하다.
- `.venv/bin/yolo version`이 실패한다.
- `model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml`이 실패한다.
- dry-run에서 `Missing selected images`가 `0`이 아니다.
- official validation 전체 metric이 필요한데, 기존 random split 변환만 사용하려고 한다.

## 현재 판단

- 로컬 validation zip 발견 여부: 발견됨.
- v2 기준선 모델 발견 여부: 발견됨.
- 실행 환경 발견 여부: `.venv` 기준 발견됨.
- full external validation 즉시 실행 가능 여부: 불가.
- 이유: 현재 디스크 여유 공간 부족, `VL*/VS*` 전용 변환 스크립트 부재, 기존 변환 스크립트의 split/validator 한계.
