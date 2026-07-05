# 모델 학습 3일 실행 계획

> **문서 상태(2026-06-02): superseded.** 과거 tactile/v2 학습 계획이다. 현재 학습 기준은 unified 13-class 문서와 `model/README.md`를 본다.


작성 기준일: 2026-05-13 KST
실행 기간: 2026-05-14 ~ 2026-05-16

## 0. 작업 원칙

- 이 문서는 모델 학습 담당자가 3일 동안 실행할 체크리스트다.
- 프론트엔드, 백엔드, STT 문서 작업과 병렬 진행하므로 이 계획 문서 외 파일 변경은 별도 합의 전 하지 않는다.
- 데이터셋 이미지/라벨, AI Hub zip, `runs/`, `logs/`, `*.pt`, `*.onnx`는 Git에 올리지 않는다.
- 검증 산출물은 가능하면 ignored 경로인 `runs/validation/`, `runs/failure_sampling/`, `runs/export/` 아래에 둔다.
- 긴 학습이나 대용량 평가를 무심코 시작하지 않는다. 이 문서의 명령은 실행 전 입력 파일과 예상 소요 시간을 확인한다.

Git 추적 방지 확인:

```bash
git check-ignore -v \
  runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  runs/detect/walksafe_kr_tactile_v2_full/results.csv \
  datasets/walksafe_kr_v2/data.yaml \
  yolo11n.pt

git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/walksafe_kr_v2/**' 'datasets/**/images/**' 'datasets/**/labels/**'
```

완료 기준:

- `runs/`, `*.pt`, `*.onnx`, 실제 이미지/라벨이 `git status`에 나타나지 않는다.
- 추적되는 것은 `.gitkeep` 또는 문서/스크립트처럼 민감 데이터가 아닌 파일뿐이다.

## 1. 현재 v2 기준선

프로젝트 PDF의 모델 목표는 스마트폰 바디캠 시점의 실시간 위험 탐지, 경보 지연 1초 이내, 최종 정확도 90% 이상이다. 현재 v2는 이 목표를 달성한 서비스 모델이 아니라, AI Hub 513 점자블록 데이터로 만든 class 0 중심 기준선이다.

확인된 로컬 산출물:

| 항목 | 경로/값 |
| --- | --- |
| run | `runs/detect/walksafe_kr_tactile_v2_full` |
| data yaml | `datasets/walksafe_kr_v2/data.yaml` |
| train args | `runs/detect/walksafe_kr_tactile_v2_full/args.yaml` |
| results | `runs/detect/walksafe_kr_tactile_v2_full/results.csv` |
| best weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` |
| last weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt` |
| model seed | `0`, deterministic `true` |
| model | `yolo11n.pt` |
| image size | `640` |
| epochs | `50` |
| batch | `8` |

v2 validation 최종 지표:

| metric | value |
| --- | ---: |
| precision | `0.73656` |
| recall | `0.58380` |
| mAP50 | `0.66394` |
| mAP50-95 | `0.49194` |

2026-05-13 사전 실행 결과:

- artifact hash 동결 파일을 `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt`에 생성했다.
- `python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml` 검증은 통과했다.
- `best.pt` test split 별도 검증을 실행했다.
- 결과 경로: `runs/detect/runs/detect/walksafe_kr_tactile_v2_test_20260514`

| metric | value |
| --- | ---: |
| images | `2,347` |
| instances | `3,979` |
| precision | `0.728` |
| recall | `0.581` |
| mAP50 | `0.657` |
| mAP50-95 | `0.481` |

판단:

- validation mAP50-95 대비 test mAP50-95 하락은 약 `0.011`로 작다.
- v2는 기준선으로 동결 가능하다.
- recall이 여전히 낮으므로 외부 validation과 실패 프레임 분석은 계속 필요하다.
- Ultralytics가 일부 test JPEG를 로컬에서 복구 저장했으며, 해당 데이터셋은 Git ignore 대상이다.

v2 데이터셋 계약:

```yaml
path: datasets/walksafe_kr_v2
train: images/train
val: images/val
test: images/test

names:
  0: damaged_tactile_block
  1: parked_kickboard_bicycle
  2: construction_obstacle
  3: pothole
```

주의:

- `data.yaml`은 4개 클래스 계약을 유지하지만 실제 v2 학습은 `damaged_tactile_block` 중심이다.
- v2는 v1보다 개선됐지만 recall이 낮다. test split과 외부 validation을 통과하기 전에는 PWA/백엔드 연결용 최종 모델로 보지 않는다.
- `docs/model_training_status.md`에 기록된 v2 val boxes와 학습 로그 instances가 1개 차이 난다. test 검증 전에 데이터셋 검증을 다시 실행한다.

## 2. 2026-05-14: v2 동결 및 test split 검증

### 체크리스트

- [ ] v2 artifact 존재 여부를 다시 확인한다.
- [ ] v2 artifact hash manifest를 ignored 경로에 남긴다.
- [ ] 데이터셋 구조 검증을 실행한다.
- [ ] `best.pt`로 test split 별도 검증을 실행한다.
- [ ] validation 대비 test 지표 하락 폭을 기록한다.
- [ ] test 결과를 기준으로 v2를 "동결된 기준선"으로 확정하거나, 외부 검증 전 보류로 표시한다.

### artifact 동결

```bash
mkdir -p runs/validation/walksafe_kr_tactile_v2_freeze_20260514

sha256sum \
  runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt \
  runs/detect/walksafe_kr_tactile_v2_full/results.csv \
  runs/detect/walksafe_kr_tactile_v2_full/args.yaml \
  datasets/walksafe_kr_v2/data.yaml \
  > runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt

cp runs/detect/walksafe_kr_tactile_v2_full/results.csv \
  runs/detect/walksafe_kr_tactile_v2_full/args.yaml \
  datasets/walksafe_kr_v2/data.yaml \
  runs/validation/walksafe_kr_tactile_v2_freeze_20260514/
```

완료 기준:

- `SHA256SUMS.txt`에 `best.pt`, `last.pt`, `results.csv`, `args.yaml`, `data.yaml`가 모두 기록된다.
- v3/v4 학습은 새 run 이름을 사용하고 `walksafe_kr_tactile_v2_full`을 덮어쓰지 않는다.

### 데이터셋 구조 검증

```bash
python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v2/data.yaml
```

완료 기준:

- `Dataset structure is valid.`가 출력된다.
- 실패하면 test split 검증을 시작하지 않고 이미지/라벨 매칭 문제를 먼저 기록한다.

### test split 별도 검증

```bash
yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data=datasets/walksafe_kr_v2/data.yaml \
  split=test \
  imgsz=640 \
  batch=8 \
  plots=true \
  save_json=true \
  project=runs/detect \
  name=walksafe_kr_tactile_v2_test
```

완료 기준:

- 결과 폴더: `runs/detect/walksafe_kr_tactile_v2_test`
- 기록 항목: precision, recall, mAP50, mAP50-95, confusion matrix, PR curve
- test mAP50-95가 validation `0.49194` 대비 절대 `0.07` 이상 떨어지면 과적합/분할 누수/도메인 편차 위험으로 표시한다.
- test recall이 `0.50` 미만이면 실제 보행 안내용 후보에서 제외하고 실패 프레임 수집을 우선한다.

결과 기록 형식:

```text
v2_test_date:
model_sha256:
data_yaml:
test_precision:
test_recall:
test_mAP50:
test_mAP50_95:
delta_from_val_mAP50_95:
decision: baseline_frozen | hold_for_external_validation | reject_for_service_candidate
notes:
```

## 3. 2026-05-15: AI Hub 513 외부 validation 및 실패 프레임 샘플링

### 체크리스트

- [ ] AI Hub 513 공식 validation zip 보유 여부를 확인한다.
- [ ] `VL1.zip`/`VS1.zip`, `VL2.zip`/`VS2.zip` 라벨-이미지 쌍을 맞춘다.
- [ ] 외부 validation용 YOLO dataset을 ignored `runs/validation/` 아래에 생성한다.
- [ ] v2 `best.pt`로 외부 validation을 실행한다.
- [ ] 실패 유형을 최소 30장 이상 샘플링한다.
- [ ] AI Hub 159 또는 직접 촬영 1인칭 보행 영상에서 v3 후보 실패 프레임을 모은다.

### AI Hub 513 validation 입력

우선순위:

| 우선순위 | 라벨 | 이미지 | 용도 |
| ---: | --- | --- | --- |
| 1 | `VL1.zip` | `VS1.zip` | 정상 점자블록 hard negative, 오탐 확인 |
| 2 | `VL2.zip` | `VS2.zip` | 정상/불량 점자블록 외부 validation |

주의:

- 라벨 zip만으로는 검증할 수 없다. 반드시 같은 번호의 `VS*.zip` 이미지가 필요하다.
- `VL1/VS1`만 있으면 정상 점자블록 오탐 검증에 가깝다. 불량 recall까지 보려면 `VL2/VS2`가 필요하다.
- `VS1.zip`, `VS2.zip`은 대용량이므로 다운로드와 압축 해제 위치는 Git 밖 또는 ignored 경로로 둔다.

### 외부 validation dataset 생성

현재 저장소의 변환 스크립트는 `TL8/TL9/TS8/TS9` 이름을 찾는다. 코드 변경 없이 공식 validation을 재사용하려면 ignored 경로에 symlink를 만들고 기존 스크립트가 validation zip을 학습 zip처럼 읽게 한다.

```bash
AIHUB513_ROOT="$HOME/Downloads/119.보행 안전을 위한 도로 시설물 데이터"
LINK_ROOT="runs/validation/aihub513_val_links"
TARGET="runs/validation/aihub513_official_val_dataset"

mkdir -p "$LINK_ROOT"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VL1.zip' -print -quit)" "$LINK_ROOT/TL8.zip"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VL2.zip' -print -quit)" "$LINK_ROOT/TL9.zip"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VS1.zip' -print -quit)" "$LINK_ROOT/TS8.zip"
ln -sfn "$(find "$AIHUB513_ROOT" -name 'VS2.zip' -print -quit)" "$LINK_ROOT/TS9.zip"

python data_sources/scripts/build_walksafe_kr_tactile.py \
  --download-root "$LINK_ROOT" \
  --target "$TARGET" \
  --max-positive 0 \
  --max-negative 0 \
  --val-ratio 0 \
  --test-ratio 0

cat > "$TARGET/data.yaml" <<YAML
path: $TARGET
train: images/train
val: images/train
test: images/train

names:
  0: damaged_tactile_block
  1: parked_kickboard_bicycle
  2: construction_obstacle
  3: pothole
YAML

python model/validate_yolo_dataset.py --data "$TARGET/data.yaml"
```

완료 기준:

- `Missing selected images: 0`
- `python model/validate_yolo_dataset.py` 통과
- `runs/validation/aihub513_official_val_dataset/BUILD_SUMMARY.md`에 positive/negative/boxes 수가 기록된다.

### AI Hub 513 외부 validation 실행

```bash
yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data=runs/validation/aihub513_official_val_dataset/data.yaml \
  split=val \
  imgsz=640 \
  batch=8 \
  plots=true \
  save_json=true \
  project=runs/detect \
  name=walksafe_kr_tactile_v2_aihub513_official_val
```

완료 기준:

- 결과 폴더: `runs/detect/walksafe_kr_tactile_v2_aihub513_official_val`
- 외부 validation mAP50-95가 v2 validation `0.49194`보다 크게 낮으면 domain shift로 판단한다.
- 외부 validation recall이 `0.45` 미만이면 v2를 통합 서비스 후보에서 제외하고 v3 데이터 보강을 먼저 진행한다.

### 실패 프레임 샘플링

검증 이미지 또는 직접 촬영/AI Hub 159 1인칭 보행 영상에서 추론 결과를 저장한다.

```bash
SOURCE="/path/to/walk_or_validation_images_or_video"

yolo detect predict \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  source="$SOURCE" \
  imgsz=640 \
  conf=0.15 \
  iou=0.7 \
  vid_stride=10 \
  save=true \
  save_txt=true \
  save_conf=true \
  project=runs/failure_sampling \
  name=v2_failure_candidates_20260515
```

샘플링 기준:

| bucket | 조건 | v3 반영 방식 |
| --- | --- | --- |
| `missed_defect` | 사람이 보면 불량 점자블록인데 미탐 | positive 보강 |
| `false_positive_normal_tactile` | 정상 점자블록을 불량으로 탐지 | hard negative 보강 |
| `low_light_or_blur` | 야간, 역광, 흔들림으로 미탐/오탐 | 직접 촬영/증강 후보 |
| `small_or_far` | 너무 작거나 먼 객체 | 라벨링 제외 기준 재검토 |
| `new_class_gap` | 킥보드/공사물/포트홀인데 class 0 모델이 대응 불가 | v4 4-class 데이터로 분리 |

완료 기준:

- 최소 30장 이상 실패 후보를 사람이 검수한다.
- 각 후보는 `source`, `expected_class`, `failure_type`, `action`을 기록한다.
- 동일 장소 연속 프레임은 대표 프레임만 남긴다.
- 얼굴, 차량번호, 민감 위치 정보가 보이면 v3 후보 편입 전에 비식별 처리한다.

AI Hub 159는 즉시 재학습용이 아니라 스마트폰/PWA 사용 시점과 가까운 failure mining용이다. 1차 대상은 `2.Validation/BBOX/Average_stature/out`의 `School`, `Building_area`, `Bridge`이고, `Park`, `Residential_area`, `Market`은 용량 여유가 있을 때만 받는다.

## 4. 2026-05-16: ONNX export, 동등성, latency, v3/v4 계획 확정

### 체크리스트

- [ ] v2 `best.pt`를 ONNX로 export한다.
- [ ] ONNX 파일 hash와 export 설정을 기록한다.
- [ ] PT와 ONNX를 같은 test split에서 비교한다.
- [ ] 로컬 latency를 PT/ONNX 각각 측정한다.
- [ ] PWA/백엔드 연결 후보를 `server`, `onnx`, `hold` 중 하나로 결정한다.
- [ ] v3/v4 데이터 보강 계획과 다음 학습 run 이름을 확정한다.

### ONNX export

```bash
yolo export \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  format=onnx \
  imgsz=640 \
  opset=12 \
  simplify=true

sha256sum \
  runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx \
  > runs/validation/walksafe_kr_tactile_v2_freeze_20260514/ONNX_SHA256SUMS.txt
```

완료 기준:

- `runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx` 생성
- `.onnx`가 `git status`에 나타나지 않음
- export 실패 시 오류 로그와 ultralytics/onnx 버전을 기록하고 백엔드 연결은 보류한다.

### PT vs ONNX metric 동등성

```bash
yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data=datasets/walksafe_kr_v2/data.yaml \
  split=test \
  imgsz=640 \
  batch=8 \
  project=runs/detect \
  name=walksafe_kr_tactile_v2_test_pt_equivalence

yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx \
  data=datasets/walksafe_kr_v2/data.yaml \
  split=test \
  imgsz=640 \
  batch=8 \
  project=runs/detect \
  name=walksafe_kr_tactile_v2_test_onnx_equivalence
```

완료 기준:

- ONNX mAP50-95가 PT 대비 절대 `0.01` 이내
- ONNX precision/recall이 PT 대비 절대 `0.02` 이내
- 클래스 순서가 `data.yaml`과 동일함

### PT vs ONNX latency 측정

짧은 로컬 측정은 같은 이미지 100~200장을 대상으로 한다. 이 수치는 모바일 최종 지표가 아니라 export 후보 선별용이다.

```bash
python - <<'PY'
from pathlib import Path
from statistics import mean
from time import perf_counter
from ultralytics import YOLO

images = sorted(Path("datasets/walksafe_kr_v2/images/test").glob("*"))[:200]
if not images:
    raise SystemExit("No test images found")

models = [
    "runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt",
    "runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx",
]

for model_path in models:
    model = YOLO(model_path)
    model.predict(source=str(images[0]), imgsz=640, verbose=False)
    times = []
    for image in images:
        start = perf_counter()
        model.predict(source=str(image), imgsz=640, verbose=False)
        times.append((perf_counter() - start) * 1000)
    times_sorted = sorted(times)
    p50 = times_sorted[len(times_sorted) // 2]
    p95 = times_sorted[int(len(times_sorted) * 0.95) - 1]
    print(f"{model_path}, n={len(times)}, mean_ms={mean(times):.2f}, p50_ms={p50:.2f}, p95_ms={p95:.2f}")
PY
```

완료 기준:

- ONNX p95가 PT p95의 `1.2x` 이내거나, 백엔드 서버 후보로 p95 `250ms` 이내면 통과 후보로 본다.
- 실제 PWA 최종 기준은 카메라 프레임 캡처부터 TTS/경보까지 p95 `1초 이내`다. 이 기준은 모바일 브라우저 연결 뒤 다시 측정한다.
- latency가 느리면 `imgsz=512`, 프레임 샘플링, 서버 추론, TensorFlow.js 비교를 검토한다.

### 연결 후보 결정

| 결정 | 조건 | 다음 액션 |
| --- | --- | --- |
| `server` | ONNX export는 통과하지만 브라우저 성능 검증 전 | 백엔드 ONNX Runtime adapter 후보로 전달 |
| `onnx` | ONNX metric/latency가 통과하고 PWA 실험 준비 가능 | 프론트에 ONNX Runtime Web PoC 요청 |
| `hold` | test/external validation 또는 export 동등성 실패 | v3 데이터 보강 후 재평가 |

초기 confidence 후보:

- API 연결 smoke: `0.15`
- 데모/신고 후보: `0.35`
- 자동 위험 안내 후보: `0.50` 이상에서 false positive 확인 후 결정

## 5. v3/v4 데이터 보강 계획

### v3: v2 실패 보정 데이터셋

목표:

- v2의 점자블록 미탐/오탐을 줄인다.
- 한국 1인칭 스마트폰 보행 시점과 정상 점자블록 hard negative를 보강한다.
- 여전히 class 0 중심이므로 4-class 서비스 모델로 과대표기하지 않는다.

권장 경로:

```text
runs/failure_sampling/v2_failure_candidates_20260515/
datasets/walksafe_kr_v3/
runs/detect/walksafe_kr_tactile_v3_failure_ft/
logs/walksafe_kr_tactile_v3_failure_ft.log
```

주의:

- 3일 실행 기간에는 raw/candidate 이미지를 우선 `runs/failure_sampling/` 또는 `runs/curation/` 아래에 둔다.
- `datasets/walksafe_kr_v3/`를 만들 때는 `images/`, `labels/`, `data.yaml`, `BUILD_SUMMARY.md`가 Git에 잡히지 않는지 먼저 확인한다.
- 현재 `.gitignore`는 `datasets/**/images/**`, `datasets/**/labels/**`를 막지만 새 데이터셋의 `data.yaml`과 summary는 자동으로 막지 않을 수 있다. 확신이 없으면 commit하지 않는다.

확인 명령:

```bash
git check-ignore -v datasets/walksafe_kr_v3/images/train/example.jpg datasets/walksafe_kr_v3/labels/train/example.txt
git status --short -- datasets/walksafe_kr_v3 datasets/walksafe_kr_v4
```

데이터 기준:

- `missed_defect` positive 200장 이상
- `false_positive_normal_tactile` hard negative 200장 이상
- 직접 촬영 또는 AI Hub 159 1인칭 시점 이미지 200장 이상
- 같은 장소/날짜/연속 프레임은 같은 split에만 배치
- val/test는 원본 이미지로 유지하고 증강 이미지를 넣지 않음

학습 후보 명령:

```bash
python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v3/data.yaml

python model/train_yolo.py \
  --data datasets/walksafe_kr_v3/data.yaml \
  --model runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  --epochs 30 \
  --imgsz 640 \
  --batch 8 \
  --name walksafe_kr_tactile_v3_failure_ft
```

v3 완료 기준:

- v2 test split과 AI Hub 513 external validation을 모두 재평가한다.
- recall이 v2 대비 상승하고 mAP50-95가 v2 대비 `0.02` 이상 하락하지 않아야 한다.
- 정상 점자블록 hard negative에서 false positive가 줄어야 한다.

### v4: 4-class 통합 모델

목표:

- 프로젝트 PDF의 실제 위험 요소인 파손 점자블록, 방치 킥보드/자전거, 공사 장애물, 포트홀을 모두 다룬다.
- PWA/백엔드 `DetectionEvent`의 4개 class 계약과 실제 모델 출력을 맞춘다.

보강 우선순위:

| class | 최소 목표 | 데이터 출처 |
| --- | ---: | --- |
| `damaged_tactile_block` | v3 통과 데이터 유지 | AI Hub 513, 직접 촬영, AI Hub 159 |
| `parked_kickboard_bicycle` | 300 positive, 300 hard negative | 직접 촬영, 한국 보도 이미지, AI Hub 159 |
| `construction_obstacle` | 300 positive, 300 hard negative | 직접 촬영, 공사 구간 보도 이미지 |
| `pothole` | 300 positive, 300 hard negative | 한국 보도/이면도로 이미지, AI Hub/공공 데이터 |

증강 원칙:

- train split에만 밝기/채도 변화, 약한 회전, scale, translate, motion blur 후보를 적용한다.
- val/test는 실제 원본만 사용한다.
- 작은 객체를 무리하게 positive로 넣지 않는다. 사람이 구분하기 어려우면 라벨링 제외한다.
- 정상 점자블록, 정상 보도블록, 보행 동선을 막지 않는 자전거/킥보드는 hard negative로 남긴다.

v4 학습 후보:

```bash
python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v4/data.yaml

python model/train_yolo.py \
  --data datasets/walksafe_kr_v4/data.yaml \
  --model yolo11n.pt \
  --epochs 80 \
  --imgsz 640 \
  --batch 8 \
  --name walksafe_kr_4class_v4_full
```

v4 완료 기준:

- class별 precision/recall/mAP를 따로 기록한다.
- 전체 mAP 평균만으로 통과시키지 않는다.
- 어떤 클래스도 recall `0.60` 미만이면 서비스 자동 안내 후보에서 제외하고 TTS/신고 threshold를 클래스별로 제한한다.
- 최종 목표는 한국 validation 기준 mAP `0.90` 이상과 경보 지연 p95 `1초 이내`다. 5월 16일까지는 달성 여부보다 측정 파이프라인 완성이 우선이다.

## 6. 최종 3일 산출물

2026-05-16 종료 시점에 남겨야 할 결과:

- v2 freeze manifest: `runs/validation/walksafe_kr_tactile_v2_freeze_20260514/SHA256SUMS.txt`
- v2 test split 결과: `runs/detect/walksafe_kr_tactile_v2_test`
- AI Hub 513 외부 validation 결과: `runs/detect/walksafe_kr_tactile_v2_aihub513_official_val`
- 실패 샘플링 결과: `runs/failure_sampling/v2_failure_candidates_20260515`
- ONNX export: `runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`
- PT vs ONNX 비교 기록: `runs/detect/walksafe_kr_tactile_v2_test_pt_equivalence`, `runs/detect/walksafe_kr_tactile_v2_test_onnx_equivalence`
- v3/v4 진행 결정: `baseline_frozen`, `server/onnx/hold`, 다음 데이터 보강 bucket

Git 확인:

```bash
git status --short
git status --ignored --short | head -80
```

완료 기준:

- Git 변경은 문서 또는 의도한 코드 변경만 보인다.
- 데이터셋/가중치/이미지/라벨/ONNX/PT는 ignored 상태로만 보인다.
- v2가 통과하지 못한 항목은 "실패"가 아니라 v3/v4 보강 bucket으로 분류되어 다음 액션이 남는다.
