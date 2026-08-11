# Android/static threshold evidence note - 2026-06-01

## 목적

Android native TFLite threshold와 backend Stage1 threshold를 섞지 않기 위해, 현재 로컬에서 바로 쓸 수 있는 static RGB 산출물과 그 한계를 정리한다.

## 결론

- Android threshold와 backend threshold는 현재 **분리 유지**한다.
- Android threshold는 on-device recall, overlay 관찰, bbox/depth gate 확인 목적이다.
- backend threshold는 `/reports/v2` 운영 precision과 false positive 부담 관리 목적이다.
- static RGB 산출물은 detector threshold tradeoff 참고용이다. ARCore depth, bbox-depth 정합, `N보` 정확도 PASS 근거가 아니다.
- full static metric rerun은 reviewed tactile3 원본 image/GT dataset 부재로 blocked다.

## artifact 출처

| artifact | path | 쓸 수 있는 근거 | 한계 |
|---|---|---|---|
| Stage1 reviewed test presence sweep | `runs/reports/stage1_yolo26s_reviewed_test_presence_20260523_203422/SUMMARY.md` | damaged_tactile_block image-level threshold tradeoff | fresh rerun 아님. 현재 selected reviewed dataset 부재 상태에서는 재현 불가 |
| Stage1 FP/FN candidates | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/SUMMARY.md` | threshold별 FP/FN 후보 목록 위치 | 원본 이미지/GT label을 다시 읽은 full eval 아님 |
| predictions manifest presence sweep | `runs/reports/predictions_manifest_presence_20260531/SUMMARY.md` | predictions.json + manifest 기반 image-level presence 참고 | bbox IoU/mAP 아님. target_category_id 1 presence 기준 |
| Android TFLite config | `apps/android/app/src/main/assets/model-config/two_model_runtime.json` | Android runtime threshold/source of truth | backend threshold와 다름. 실기기 품질 PASS 아님 |
| backend Stage1 config | `configs/walksafe_two_model_runtime_stage1_mvp_20260523.json` | backend Stage1 threshold/source of truth | Android on-device recall 기준과 다름 |

## Android/backend threshold 비교

| class/model | Android threshold | backend threshold | 현재 결정 | 주의 |
|---|---:|---:|---|---|
| `unified_walksafe:damaged_tactile_block` | 0.35 | 0.35 | 분리 유지 | primary 단일 모델의 report target. Android/backend 목적은 여전히 분리 |
| `unified_walksafe:normal_tactile_block` | 0.25 | 0.30 | 분리 유지 | normal tactile은 report 대상 아님 |
| `unified_walksafe:curb_step/uneven_sidewalk/e_scooter_obstruction` | 0.35 | 0.40 | 분리 유지 | 보행 위험 입력. report 대상 아님 |
| `custom_tactile:normal_tactile_block` | 0.25 | 0.45 | 분리 유지 | legacy fallback normal tactile은 report 대상 아님 |
| `custom_tactile:damaged_tactile_block` | 0.35 | 0.45 | 분리 유지 | Android 관찰 recall과 backend report precision 목적 다름 |
| `custom_tactile:tactile_damage_area` | 0.45 | 0.80 | 분리 유지 | 보조 bbox 성격. `/reports/v2` 저장 대상 아님 |
| `coco_general:person` | 0.35 | 0.20 | 분리 유지 | 일반 객체는 report 대상 아님. 보행 위험 context 필요 |
| `coco_general:traffic light` | 0.40 | 0.50 | 분리 유지 | COCO allowlist inference-only |
| `coco_general:bench` | 0.40 | 0.50 | 분리 유지 | COCO allowlist inference-only |

## Stage1 reviewed test threshold tradeoff

출처: `runs/reports/stage1_yolo26s_reviewed_test_presence_20260523_203422/SUMMARY.md`

| threshold | total | TP | FP | FN | TN | precision | recall | f1 | accuracy | source |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 0.50 | 2347 | 1072 | 62 | 119 | 1094 | 0.945326 | 0.900084 | 0.922151 | 0.922880 | saved Stage1 presence |
| 0.60 | 2347 | 1054 | 50 | 137 | 1106 | 0.954710 | 0.884971 | 0.918519 | 0.920324 | saved Stage1 presence |
| 0.70 | 2347 | 1031 | 42 | 160 | 1114 | 0.960857 | 0.865659 | 0.910777 | 0.913933 | saved Stage1 presence |
| 0.75 | 2347 | 1014 | 40 | 177 | 1116 | 0.962049 | 0.851385 | 0.903341 | 0.907542 | saved Stage1 presence |
| 0.80 | 2347 | 983 | 37 | 208 | 1119 | 0.963725 | 0.825357 | 0.889190 | 0.895611 | saved Stage1 presence |
| 0.85 | 2347 | 960 | 34 | 231 | 1122 | 0.965795 | 0.806045 | 0.878719 | 0.887090 | saved Stage1 presence |

해석:

- threshold를 올리면 FP는 줄지만 FN이 증가한다.
- backend report threshold는 FP 부담을 줄이는 쪽으로 해석할 수 있다.
- Android threshold는 실기기 overlay/depth 확인 단계에서 recall을 확보하는 쪽으로 따로 볼 수 있다.

## FP/FN candidate 파일

출처: `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/SUMMARY.md`

| threshold | total | FP | FN | candidate csv | 용도 |
|---:|---:|---:|---:|---|---|
| 0.50 | 2347 | 62 | 119 | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/presence_threshold_050_fp_fn_candidates.csv` | 낮은 threshold 후보 검토 |
| 0.60 | 2347 | 50 | 137 | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/presence_threshold_060_fp_fn_candidates.csv` | tradeoff 검토 |
| 0.70 | 2347 | 42 | 160 | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/presence_threshold_070_fp_fn_candidates.csv` | tradeoff 검토 |
| 0.75 | 2347 | 40 | 177 | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/presence_threshold_075_fp_fn_candidates.csv` | tradeoff 검토 |
| 0.80 | 2347 | 37 | 208 | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/presence_threshold_080_fp_fn_candidates.csv` | 높은 precision 후보 검토 |
| 0.85 | 2347 | 34 | 231 | `runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/presence_threshold_085_fp_fn_candidates.csv` | 높은 precision 후보 검토 |

candidate CSV 컬럼:

```text
threshold,image_id,outcome,gt_positive,pred_positive,target_prediction_count,target_max_confidence,target_top3_confidences
```

## predictions.json + manifest presence 참고

출처: `runs/reports/predictions_manifest_presence_20260531/SUMMARY.md`

| threshold | total | TP | FP | FN | TN | precision | recall | f1 | accuracy |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.30 | 2082 | 1482 | 48 | 96 | 456 | 0.968627 | 0.939163 | 0.953668 | 0.930836 |
| 0.40 | 2082 | 1463 | 38 | 115 | 466 | 0.974684 | 0.927123 | 0.950309 | 0.926513 |
| 0.50 | 2082 | 1439 | 31 | 139 | 473 | 0.978912 | 0.911914 | 0.944226 | 0.918348 |
| 0.60 | 2082 | 1404 | 22 | 174 | 482 | 0.984572 | 0.889734 | 0.934754 | 0.905860 |
| 0.70 | 2082 | 1377 | 18 | 201 | 486 | 0.987097 | 0.872624 | 0.926337 | 0.894813 |
| 0.75 | 2082 | 1353 | 18 | 225 | 486 | 0.986871 | 0.857414 | 0.917599 | 0.883285 |
| 0.80 | 2082 | 1318 | 15 | 260 | 489 | 0.988747 | 0.835234 | 0.905531 | 0.867915 |
| 0.85 | 2082 | 1270 | 11 | 308 | 493 | 0.991413 | 0.804816 | 0.888423 | 0.846782 |
| 0.90 | 2082 | 1132 | 9 | 446 | 495 | 0.992112 | 0.717364 | 0.832659 | 0.781460 |
| 0.95 | 2082 | 204 | 0 | 1374 | 504 | 1.000000 | 0.129278 | 0.228956 | 0.340058 |

제한:

- `label_box_count > 0`을 image-level GT positive로 쓴다.
- bbox IoU/mAP를 계산하지 않는다.
- saved predictions artifact와 manifest에 의존한다.
- fresh inference run이 아니다.

## full static rerun readiness

실행:

```bash
python scripts/check_static_dataset_readiness_20260531.py
```

결과:

```text
ok=false
status=blocked_for_full_static_eval_dataset_missing
blocker=datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522
```

즉시 가능한 것:

- saved Stage1 prediction labels 검토
- saved presence summary 검토
- Android/backend threshold 차이 문서화
- PT/TFLite artifact 존재 확인

불가능한 것:

- selected reviewed tactile3 dataset 기준 full static metric rerun
- fresh mAP/bbox IoU 재평가
- Android ARCore depth 또는 `N보` 정확도 검증

## PASS로 쓰면 안 되는 항목

- static RGB 결과를 ARCore depth PASS로 쓰는 것.
- static RGB 결과를 bbox-depth 정합 PASS로 쓰는 것.
- `1보` 표시가 뜬 것을 실측 거리 정확도 PASS로 쓰는 것.
- Android build 성공을 Device PASS로 쓰는 것.
- saved prediction labels/presence summary를 fresh full rerun으로 표현하는 것.
- reviewed tactile3 dataset 부재 상태에서 동일 조건 재평가 완료라고 쓰는 것.
- Android recall 목적 threshold를 backend 운영 precision PASS로 쓰는 것.
- backend report threshold를 Android 실기기 체감 품질 PASS로 쓰는 것.

## 다음 검증 명령

```bash
python scripts/check_static_dataset_readiness_20260531.py
python scripts/check_android_tflite_contract_20260531.py
sed -n '1,120p' runs/reports/stage1_yolo26s_reviewed_test_presence_20260523_203422/SUMMARY.md
sed -n '1,120p' runs/reports/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531/SUMMARY.md
sed -n '1,120p' runs/reports/predictions_manifest_presence_20260531/SUMMARY.md
```
