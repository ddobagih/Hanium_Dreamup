# 2026-05-21 v3 AI relabel interim holdout-candidate evaluation

## 목적

`datasets/walksafe_kr_v3_ai_relabel_integrated_20260521`로 학습한 v3 interim weight를 기존 v2 baseline과 같은 holdout candidate에서 비교했다.

이 평가는 **최종 blind test가 아니다**. holdout 후보는 v2 외부검증/오류분석에 이미 사용된 데이터라, v3 학습/threshold tuning에는 섞지 않는 조건의 참고 비교로만 사용한다.

## 학습 요약

| 항목 | 값 |
| --- | --- |
| train dataset | `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521/data.yaml` |
| train images | 48 |
| val images | 12 |
| total dataset boxes | 130 |
| initial weight | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` |
| epochs | 10 |
| imgsz | 640 |
| batch | 2 |
| output weight | `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/weights/best.pt` |

내부 val 결과는 `docs/execution/2026-05-21_model_v3_ai_relabel_interim_training.md`에 기록했다.

## Holdout candidate

| 항목 | 값 |
| --- | ---: |
| dataset | `datasets/walksafe_kr_v3_holdout_candidate` |
| split | `test` |
| images | 2,282 |
| manifest label boxes | 5,326 |
| Ultralytics eval instances | 5,325 |

`v3-holdout-pos-1067.jpg`에서 duplicate label 1개가 제거되어 metric instance 수는 manifest label line count보다 1 적다.

## 실행 명령

v2 baseline:

```bash
.venv/bin/yolo detect val \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  data=datasets/walksafe_kr_v3_holdout_candidate/data.yaml \
  split=test \
  imgsz=640 \
  batch=16 \
  device=0 \
  project=runs/validation \
  name=walksafe_kr_v2_baseline_holdout_candidate_20260521 \
  plots=False \
  save=False \
  verbose=True
```

v3 AI relabel interim:

```bash
.venv/bin/yolo detect val \
  model=runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/weights/best.pt \
  data=datasets/walksafe_kr_v3_holdout_candidate/data.yaml \
  split=test \
  imgsz=640 \
  batch=16 \
  device=0 \
  project=runs/validation \
  name=walksafe_kr_v3_ai_relabel_interim_holdout_candidate_20260521 \
  plots=False \
  save=False \
  verbose=True
```

## 결과

| model | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| v2 baseline | 0.743 | 0.605 | 0.676 | 0.499 |
| v3 AI relabel interim | 0.549 | 0.438 | 0.446 | 0.285 |
| delta v3-v2 | -0.194 | -0.167 | -0.230 | -0.214 |

## 해석

- 이번 10 epoch v3 AI relabel interim weight는 holdout candidate에서 v2 baseline보다 낮다.
- 현 상태로는 v2 baseline을 대체하면 안 된다.
- 이 결과는 최종 blind test가 아니지만, 최소한 현재 interim 학습 설정/데이터 균형이 충분하지 않다는 신호로 봐야 한다.
- 다음 실험은 데이터 balance, hard-negative 비율, learning schedule/epochs, freeze 여부를 분리해서 비교하는 방식이 안전하다.

## 산출물

- v2 log: `logs/walksafe_kr_v2_baseline_holdout_candidate_20260521_val.log`
- v3 log: `logs/walksafe_kr_v3_ai_relabel_interim_holdout_candidate_20260521_val.log`
- summary JSON: `data_sources/manifests/walksafe_kr_v3_ai_relabel_holdout_eval_summary_2026-05-21.json`

## 검증/주의

- 두 eval 모두 같은 holdout candidate, `split=test`, `imgsz=640`, `batch=16`, `device=0` 조건이다.
- eval 후 `datasets/walksafe_kr_v3_holdout_candidate/labels/test.cache`는 제거했다.
- holdout candidate는 최종 blind test가 아니므로 공식 최종 성능으로 보고하지 않는다.
