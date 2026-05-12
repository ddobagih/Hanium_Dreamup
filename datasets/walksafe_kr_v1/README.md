# walksafe_kr_v1 YOLO 데이터셋

한국 보행 환경 기준의 모델 v1 대상 데이터셋입니다.

이 폴더가 기본 학습/검증 대상입니다. 해외 공개 데이터로 만든 `datasets/walksafe_v1`은 smoke test 또는 pretrain 후보로만 사용합니다.

## 구조

```text
datasets/walksafe_kr_v1/
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

정상 점자블록처럼 negative로 써야 하는 이미지는 같은 이름의 빈 `.txt` 파일을 둡니다.

## 클래스

0. `damaged_tactile_block`
1. `parked_kickboard_bicycle`
2. `construction_obstacle`
3. `pothole`

## 원칙

- validation/test는 한국 데이터만 사용합니다.
- 같은 장소의 연속 프레임은 train/val/test에 섞지 않습니다.
- 얼굴, 차량번호, 민감한 위치 정보는 학습 전 비식별 처리합니다.
- 세부 수집 기준은 `docs/korean_data_strategy.md`를 따릅니다.
