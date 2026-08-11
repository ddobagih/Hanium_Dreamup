# 2026-05-21 walksafe_kr_v3 AI relabel integrated interim training

## 목적

`datasets/walksafe_kr_v3_ai_relabel_integrated_20260521` 데이터셋으로 기존 v2 best weight를 이어받아 YOLO interim training을 실행했다.

이 실행은 중간 학습 산출물 확인용이다. 아래 metric은 internal val split 기준이며, 공식 v3 성능이나 threshold tuning 근거로 사용하지 않는다.

## 실행 전 확인

| 항목 | 결과 |
| --- | --- |
| data yaml | `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521/data.yaml` 존재 |
| 초기 weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` 존재 |
| dataset split | train 48 images / val 12 images |
| label boxes | train 103 boxes / val 27 boxes |
| background labels | train 21 / val 5 |
| holdout 후보 사용 | 사용하지 않음 |
| val 사용 범위 | AI relabel integrated dataset 내부 `images/val`만 사용 |
| 실행 환경 | `.venv`, Ultralytics 8.4.48, Torch 2.11.0+cu130, CUDA:0 `NVIDIA GeForce RTX 5070 Ti` |

## 설정 선택

기존 smoke 흐름의 `imgsz 640`, `batch 2`, `device 0`를 유지했다. 다만 이번은 smoke보다 긴 interim 확인이므로 `epochs 10`으로 실행했다. 기존 runbook의 full training 예시인 50 epochs보다 작은 설정이라 장시간/대형 실험은 피했다.

## 실행 명령

```bash
.venv/bin/python model/train_yolo.py \
  --data datasets/walksafe_kr_v3_ai_relabel_integrated_20260521/data.yaml \
  --model runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  --epochs 10 \
  --imgsz 640 \
  --batch 2 \
  --device 0 \
  --project runs/detect \
  --name walksafe_kr_v3_ai_relabel_interim_20260521
```

로그 저장:

```text
logs/walksafe_kr_v3_ai_relabel_interim_train.log
```

## 결과

| 항목 | 값 |
| --- | --- |
| exit status | 0 |
| output run | `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521` |
| best weight | `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/weights/best.pt` |
| last weight | `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/weights/last.pt` |
| results csv | `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/results.csv` |
| epochs | 10 |
| imgsz | 640 |
| batch | 2 |
| device | CUDA:0 |

학습/검증 루프와 weight 저장까지 완료했다.

## Internal val metric 기록

`results.csv` 기준 mAP50-95 최고 epoch는 10이다.

| metric | value |
| --- | ---: |
| precision(B) | 0.68947 |
| recall(B) | 0.66667 |
| mAP50(B) | 0.62800 |
| mAP50-95(B) | 0.42720 |

학습 종료 후 best weight 재검증 로그:

| metric | value |
| --- | ---: |
| precision(B) | 0.697 |
| recall(B) | 0.593 |
| mAP50(B) | 0.617 |
| mAP50-95(B) | 0.424 |

해석 제한:

- 위 값은 AI relabel integrated dataset 내부 val split 기준이다.
- holdout 후보는 학습, validation, threshold tuning에 사용하지 않았다.
- 독립 holdout/test 평가 전까지 공식 v3 성능으로 보고하지 않는다.

## 산출물

- `logs/walksafe_kr_v3_ai_relabel_interim_train.log`
- `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/`
- `data_sources/manifests/walksafe_kr_v3_ai_relabel_interim_train_summary_2026-05-21.json`
- `docs/execution/2026-05-21_model_v3_ai_relabel_interim_training.md`

참고: Ultralytics가 학습 중 생성한 dataset cache 파일은 지정 쓰기 범위 밖 산출물이어서 실행 후 제거했다.
