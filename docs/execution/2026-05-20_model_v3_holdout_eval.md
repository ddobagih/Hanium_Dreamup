# 2026-05-20 v3 smoke vs v2 baseline holdout-candidate evaluation

## 목적

`datasets/walksafe_kr_v3_holdout_candidate`에서 v3 smoke weight와 기존 v2 baseline weight를 같은 조건으로 비교했다.

이 평가는 **최종 blind test가 아니다**. holdout 후보는 v2 외부검증/오류분석에 이미 사용된 데이터라, v3 학습/threshold tuning에는 섞지 않는 조건에서만 참고용 독립 후보로 사용한다.

## 데이터셋

| 항목 | 값 |
| --- | ---: |
| dataset root | `datasets/walksafe_kr_v3_holdout_candidate` |
| eval split | `test` |
| images | 2,282 |
| manifest label boxes | 5,326 |
| Ultralytics eval instances | 5,325 |

주의: `v3-holdout-pos-1067.jpg`에서 duplicate label 1개가 제거되어, Ultralytics metric의 instance 수는 manifest label line count보다 1 적다.

## 실행 명령

v3 smoke:

```bash
.venv/bin/yolo detect val \
  model=runs/detect/walksafe_kr_v3_smoke_20260520/weights/best.pt \
  data=datasets/walksafe_kr_v3_holdout_candidate/data.yaml \
  split=test \
  imgsz=640 \
  batch=16 \
  device=0 \
  project=runs/validation \
  name=walksafe_kr_v3_smoke_holdout_candidate_20260520 \
  plots=False \
  save=False \
  verbose=True
```

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
  name=walksafe_kr_v2_baseline_holdout_candidate_20260520 \
  plots=False \
  save=False \
  verbose=True
```

## 결과

| model | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| v2 baseline | 0.743 | 0.605 | 0.676 | 0.499 |
| v3 smoke | 0.755 | 0.590 | 0.671 | 0.494 |
| delta v3-v2 | +0.012 | -0.015 | -0.005 | -0.005 |

해석:

- v3 smoke는 precision이 조금 높고, recall/mAP는 v2보다 조금 낮다.
- 하지만 v3는 35장 unique staging 데이터로 1 epoch만 돌린 smoke weight라서, 개선/퇴보 결론으로 쓰면 안 된다.
- 공식 보고용 v3 metric은 full training 이후, 가능하면 v2 분석에 쓰지 않은 blind holdout/test에서 다시 내야 한다.

## 로그와 메타데이터

- v3 log: `logs/walksafe_kr_v3_smoke_holdout_candidate_val.log`
- v2 log: `logs/walksafe_kr_v2_baseline_holdout_candidate_val.log`
- summary JSON: `data_sources/manifests/walksafe_kr_v3_holdout_eval_summary_2026-05-20.json`

## 검증 중 관찰사항

- 두 실행 모두 `corrupt JPEG restored and saved` warning이 157회 기록됐다.
- holdout candidate는 symlink 기반이므로, Ultralytics/PIL의 restore 동작이 원본 target image를 수정했을 가능성이 있다.
- Ultralytics가 만든 `datasets/walksafe_kr_v3_holdout_candidate/labels/test.cache`는 metric 기록 후 제거했다.
