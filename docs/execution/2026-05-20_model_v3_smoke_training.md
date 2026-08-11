# 2026-05-20 walksafe_kr_v3 smoke training

## 목적

Materialized `datasets/walksafe_kr_v3`가 준비된 뒤, 기존 v2 best weight를 초기값으로 사용해 YOLO 학습 파이프라인이 짧게 끝까지 도는지 확인했다.

이 실행은 smoke 확인 전용이다. 아래 metric은 공식 성능, v3 개선 수치, threshold tuning 근거로 사용하지 않는다.

## 실행 전 확인

| 항목 | 결과 |
| --- | --- |
| `datasets/walksafe_kr_v3/data.yaml` | 존재 |
| 초기 weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` 존재 |
| dataset split | train 28 images / val 7 images |
| holdout 후보 사용 | 사용하지 않음 |
| val 사용 범위 | materialized staging 내부 `images/val`만 사용 |
| 실행 환경 | `.venv`, Ultralytics 8.4.48, Torch 2.11.0+cu130, CUDA:0 |

참고: Ultralytics가 학습 중 생성한 dataset cache 파일은 지정 쓰기 범위 밖 산출물이어서 실행 후 제거했다.

## 실행 명령

```bash
.venv/bin/python model/train_yolo.py \
  --data datasets/walksafe_kr_v3/data.yaml \
  --model runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  --epochs 1 \
  --imgsz 640 \
  --batch 2 \
  --device 0 \
  --project runs/detect \
  --name walksafe_kr_v3_smoke_20260520
```

로그 저장:

```bash
logs/walksafe_kr_v3_smoke_train.log
```

## 결과

| 항목 | 값 |
| --- | --- |
| exit status | 0 |
| output run | `runs/detect/walksafe_kr_v3_smoke_20260520` |
| best weight | `runs/detect/walksafe_kr_v3_smoke_20260520/weights/best.pt` |
| last weight | `runs/detect/walksafe_kr_v3_smoke_20260520/weights/last.pt` |
| epoch | 1 |
| imgsz | 640 |
| batch | 2 |
| device | CUDA:0 |

Smoke run은 학습/검증 루프와 weight 저장까지 완료했다.

## Smoke metric 기록(공식 성능 아님)

`runs/detect/walksafe_kr_v3_smoke_20260520/results.csv`의 1 epoch 결과:

| metric | value |
| --- | ---: |
| precision(B) | 0.44099 |
| recall(B) | 0.75 |
| mAP50(B) | 0.62397 |
| mAP50-95(B) | 0.45985 |
| val images | 7 |
| val instances | 8 |

해석 제한:

- 위 값은 smoke 통과 여부 확인용이다.
- holdout 후보는 학습, validation, threshold tuning에 사용하지 않았다.
- 독립 holdout/test 평가 전까지 공식 v3 성능으로 보고하지 않는다.

## 산출물

- `logs/walksafe_kr_v3_smoke_train.log`
- `runs/detect/walksafe_kr_v3_smoke_20260520/`
- `data_sources/manifests/walksafe_kr_v3_smoke_train_summary_2026-05-20.json`
