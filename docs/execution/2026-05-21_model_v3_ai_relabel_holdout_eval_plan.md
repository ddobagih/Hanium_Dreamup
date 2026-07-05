# 2026-05-21 v3 AI relabel interim holdout eval plan

## 목적

`datasets/walksafe_kr_v3_ai_relabel_integrated_20260521`로 학습되는 v3 interim weight를 기존 v2 baseline weight와 같은 holdout candidate에서 비교한다.

이 평가는 **최종 blind test가 아니다**. `datasets/walksafe_kr_v3_holdout_candidate`는 v2 외부검증/오류분석에 이미 사용된 후보 데이터이므로, v3 학습/threshold tuning에는 섞지 않고 참고용 비교로만 사용한다.

## 사전 검증 결과

검증 스크립트:

```bash
python data_sources/scripts/validate_yolo_dataset.py <dataset>
```

로그: `logs/walksafe_kr_v3_ai_relabel_eval_prep_validator.log`

| dataset | split summary | result |
| --- | --- | --- |
| `datasets/walksafe_kr_v3_ai_relabel_integrated_20260521` | train 48 images / 103 boxes, val 12 images / 27 boxes | ok |
| `datasets/walksafe_kr_v3_holdout_candidate` | train/val/test each 2,282 images / 5,326 boxes | ok |

## 비교 대상

| 구분 | weight | data | split |
| --- | --- | --- | --- |
| v2 baseline | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` | `datasets/walksafe_kr_v3_holdout_candidate/data.yaml` | `test` |
| v3 interim | trainer 산출물의 `weights/best.pt` 또는 지정 weight | `datasets/walksafe_kr_v3_holdout_candidate/data.yaml` | `test` |

v3 interim 산출물이 아직 없으면 v3 eval은 실행하지 않는다.

## 평가 명령 템플릿

### v2 baseline 재평가

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
  verbose=True \
  2>&1 | tee logs/walksafe_kr_v2_baseline_holdout_candidate_20260521_val.log
```

### v3 interim 평가

`<V3_INTERIM_WEIGHT>`와 `<V3_INTERIM_EVAL_NAME>`은 trainer 에이전트의 실제 산출물 경로/이름으로 교체한다.

```bash
.venv/bin/yolo detect val \
  model=<V3_INTERIM_WEIGHT> \
  data=datasets/walksafe_kr_v3_holdout_candidate/data.yaml \
  split=test \
  imgsz=640 \
  batch=16 \
  device=0 \
  project=runs/validation \
  name=<V3_INTERIM_EVAL_NAME> \
  plots=False \
  save=False \
  verbose=True \
  2>&1 | tee logs/<V3_INTERIM_EVAL_NAME>_val.log
```

예상 weight 형식:

```text
runs/detect/<trainer-run-name>/weights/best.pt
```

## v3 interim holdout eval 실행 기록

평가 준비 중 trainer 산출물이 확인되어 v3 interim holdout eval을 실행했다.

- weight: `runs/detect/walksafe_kr_v3_ai_relabel_interim_20260521/weights/best.pt`
- log: `logs/walksafe_kr_v3_ai_relabel_interim_holdout_candidate_20260521_val.log`
- exit status: `0`
- note: Ultralytics가 생성한 `datasets/walksafe_kr_v3_holdout_candidate/labels/test.cache`는 평가 후 제거했다.

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
  verbose=True \
  2>&1 | tee logs/walksafe_kr_v3_ai_relabel_interim_holdout_candidate_20260521_val.log
```

| model | images | instances | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| v3 AI relabel interim | 2,282 | 5,325 | 0.549 | 0.438 | 0.446 | 0.285 |

비교 기준 v2 baseline은 같은 holdout candidate에서 `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt`로 재평가하거나, 기존 `docs/execution/2026-05-20_model_v3_holdout_eval.md`의 참고 수치와 비교한다.

## 결과 기록 템플릿

평가 완료 후 같은 holdout candidate `test` split 기준으로 아래 표를 채운다.

| model | precision | recall | mAP50 | mAP50-95 | log |
| --- | ---: | ---: | ---: | ---: | --- |
| v2 baseline | TBD | TBD | TBD | TBD | `logs/walksafe_kr_v2_baseline_holdout_candidate_20260521_val.log` |
| v3 interim | TBD | TBD | TBD | TBD | `logs/<V3_INTERIM_EVAL_NAME>_val.log` |
| delta v3-v2 | TBD | TBD | TBD | TBD | - |

## 판정 기준

- 같은 holdout candidate, 같은 split, 같은 `imgsz=640`, 같은 batch/device 조건으로 비교한다.
- v3 interim이 v2 대비 개선됐는지 여부는 최소한 `mAP50-95`, `mAP50`, recall/precision trade-off를 함께 본다.
- 이 holdout candidate는 최종 blind test가 아니므로, 공식 최종 성능으로 과대해석하지 않는다.
