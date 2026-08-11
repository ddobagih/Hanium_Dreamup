# model v3 YOLO dataset validator

## 범위

- 작성 스크립트: `data_sources/scripts/validate_yolo_dataset.py`
- 검증 대상:
  - `datasets/walksafe_kr_v3`
  - `datasets/walksafe_kr_v3_holdout_candidate`
- 실행 로그: `logs/walksafe_kr_v3_validator.log`

## 스크립트 검사 항목

`data_sources/scripts/validate_yolo_dataset.py`는 dataset root 또는 `data.yaml` 경로를 인자로 받는다.

검사 내용:

- `data.yaml`의 `path`, `train`, `val`, 선택적 `test`, `names` 파싱
- split별 image dir에서 대응 label dir 추론
  - 예: `images/train` -> `labels/train`
  - holdout처럼 `train/val/test`가 모두 `images/test`를 가리키는 설정도 검증 가능
- 이미지별 동일 stem의 label `.txt` 존재 여부
- dataset root 하위 dangling symlink 존재 여부
- label 라인의 YOLO 형식
  - `class x y w h` 5개 필드
  - class id 정수
  - `x/y/w/h` float
  - `0 <= x,y,w,h <= 1`
  - `w/h > 0`
- class id가 `data.yaml`의 `names` 범위 안인지 여부
- empty label 파일 허용
- split별 image/label/box count 출력

## 실행 결과

명령:

```bash
python3 -m py_compile data_sources/scripts/validate_yolo_dataset.py
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3
python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_holdout_candidate
```

결과:

```text
$ python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3
dataset_root: /home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_v3
data_yaml: datasets/walksafe_kr_v3/data.yaml
classes: 4
train: images=28 labels=28 boxes=28
val: images=7 labels=7 boxes=8
validation: ok

$ python3 data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_v3_holdout_candidate
dataset_root: /home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_v3_holdout_candidate
data_yaml: datasets/walksafe_kr_v3_holdout_candidate/data.yaml
classes: 4
train: images=2282 labels=2282 boxes=5326
val: images=2282 labels=2282 boxes=5326
test: images=2282 labels=2282 boxes=5326
validation: ok
```

판정:

- `datasets/walksafe_kr_v3` symlink 기반 YOLO train/val dataset 기본 구조/라벨 검증 통과.
- `datasets/walksafe_kr_v3_holdout_candidate` symlink 기반 YOLO evaluation candidate dataset 기본 구조/라벨 검증 통과.

주의:

- validator는 label line 자체를 세므로 holdout box count를 5,326개로 출력한다.
- Ultralytics `val`은 duplicate label 1개를 제거해 metric instance를 5,325개로 계산했다.
