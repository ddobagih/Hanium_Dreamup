# 모델 개발

이 폴더는 YOLO 기반 모델 v1 개발을 위한 스크립트를 담습니다.

## 데이터셋 검증

```bash
python model/validate_yolo_dataset.py
```

검증 항목:

- `data.yaml` 클래스 정의
- train/val/test 이미지 및 라벨 폴더 존재 여부
- 이미지와 라벨 파일 매칭
- 라벨 행 형식
- 클래스 ID 범위
- bbox 좌표가 0~1 사이인지 여부

## 학습 실행

```bash
python model/train_yolo.py \
  --data datasets/walksafe_kr_v1/data.yaml \
  --model yolo11n.pt \
  --epochs 50 \
  --imgsz 640 \
  --batch 8
```

`ultralytics`가 설치되어 있지 않으면 `requirements-model.txt`를 먼저 설치합니다.

```bash
python -m pip install -r requirements-model.txt
```

## 주의

데이터가 없는 상태에서는 학습을 실행하지 않습니다. 먼저 `datasets/walksafe_kr_v1/images`와 `datasets/walksafe_kr_v1/labels`에 한국 기준 YOLO 형식 데이터를 채웁니다.

해외 공개 baseline 데이터셋은 다음처럼 명시적으로 지정할 때만 사용합니다.

```bash
python model/train_yolo.py --data datasets/walksafe_v1/data.yaml --epochs 1 --batch 4 --name walksafe_public_smoke
```
