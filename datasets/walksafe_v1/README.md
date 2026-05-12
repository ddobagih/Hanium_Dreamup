# walksafe_v1 YOLO 데이터셋

해외 공개 데이터 기반 smoke/pretrain 후보 데이터셋 폴더입니다.

실제 모델 v1의 기본 학습/검증 대상은 `datasets/walksafe_kr_v1`입니다. 이 데이터셋은 최종 한국 기준 성능 평가에 사용하지 않습니다.

## 구조

```text
datasets/walksafe_v1/
  images/
    train/
    val/
    test/
  labels/
    train/
    val/
    test/
  data.yaml
```

## 라벨 형식

각 이미지와 같은 파일명을 가진 `.txt` 파일을 `labels/{split}/`에 둡니다.

```text
class_id x_center y_center width height
```

모든 좌표는 0~1 사이로 정규화된 YOLO 형식이어야 합니다.

## 클래스

0. `damaged_tactile_block`
1. `parked_kickboard_bicycle`
2. `construction_obstacle`
3. `pothole`
