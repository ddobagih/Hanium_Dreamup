# YOLO26s tactile 3-class Stage1/Stage2 review

Date: 2026-05-22 KST

## Scope

This review covers the 4 requested follow-up items after the YOLO26s tactile 3-class run stopped:

1. Generate CPU-only run summaries.
2. Compare Stage1 `best.pt` vs Stage2 `best.pt`.
3. Review `tactile_damage_area` sample/error signals from existing artifacts.
4. Propose threshold candidates for app/MVP integration.

Guardrails:

- No additional training was started.
- No GPU inference/evaluation was run for this review.
- Metrics and findings below are based on existing `results.csv`, existing validation outputs, and the resume pipeline log.
- Visual sample review was limited to the saved validation artifacts (`val_batch*_labels.jpg`, `val_batch*_pred.jpg`, confusion/PR/F1/recall curves). It is not an exhaustive per-image relabeling pass.

## Generated local reports

CPU-only parser outputs:

- `runs/reports/walksafe_tactile3_yolo26s_20260522/stage1_summary.json`
- `runs/reports/walksafe_tactile3_yolo26s_20260522/stage1_summary.md`
- `runs/reports/walksafe_tactile3_yolo26s_20260522/stage2_summary.json`
- `runs/reports/walksafe_tactile3_yolo26s_20260522/stage2_summary.md`

Source runs:

| Stage | Run dir | Best checkpoint | Completed epochs | Stop reason |
| --- | --- | --- | ---: | --- |
| Stage1 | `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521` | `weights/best.pt` | 106 / 200 | EarlyStopping, no improvement in 50 epochs, best epoch 56 |
| Stage2 | `runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521` | `weights/best.pt` | 33 / 80 | EarlyStopping, no improvement in 25 epochs, best epoch 8 |

## 1. Training result comparison

`results.csv` 기준 핵심 epoch 비교:

| Stage | Selection | Epoch | Precision | Recall | mAP50 | mAP50-95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Stage1 | last | 106 | 0.80986 | 0.79070 | 0.82360 | 0.73111 |
| Stage1 | best mAP50-95 | 56 | 0.81247 | 0.78667 | 0.82671 | 0.73697 |
| Stage1 | best mAP50 | 92 | 0.82101 | 0.79417 | 0.82848 | 0.73655 |
| Stage2 | last | 33 | 0.80770 | 0.79032 | 0.81097 | 0.71914 |
| Stage2 | best mAP50-95 | 8 | 0.80562 | 0.79362 | 0.82278 | 0.72944 |
| Stage2 | best mAP50 | 2 | 0.80308 | 0.79837 | 0.82508 | 0.72050 |

Training-side conclusion:

- Stage1 best mAP50-95 is higher than Stage2 best mAP50-95 by about `+0.00753`.
- Stage2 did not improve enough to justify replacing Stage1 as the default candidate.
- Same configuration으로 이어서 더 학습하는 것은 권장하지 않는다. 두 단계 모두 EarlyStopping으로 멈췄고, Stage2의 추가 fine-tune이 성능 개선으로 이어지지 않았다.

## 2. Test/validation output comparison

Resume pipeline log의 test validation 결과:

| Stage | Class | Images | Instances | Precision | Recall | mAP50 | mAP50-95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Stage1 | all | 2347 | 5135 | 0.807 | 0.785 | 0.820 | 0.730 |
| Stage1 | `normal_tactile_block` | 1156 | 1156 | 0.883 | 0.951 | 0.965 | 0.952 |
| Stage1 | `damaged_tactile_block` | 1191 | 1191 | 0.895 | 0.936 | 0.968 | 0.944 |
| Stage1 | `tactile_damage_area` | 1191 | 2788 | 0.644 | 0.467 | 0.528 | 0.293 |
| Stage2 | all | 2347 | 5135 | 0.790 | 0.792 | 0.819 | 0.726 |
| Stage2 | `normal_tactile_block` | 1156 | 1156 | 0.882 | 0.948 | 0.963 | 0.949 |
| Stage2 | `damaged_tactile_block` | 1191 | 1191 | 0.887 | 0.939 | 0.968 | 0.938 |
| Stage2 | `tactile_damage_area` | 1191 | 2788 | 0.602 | 0.490 | 0.525 | 0.291 |

Decision:

- **Current app/MVP candidate: Stage1 `best.pt`.**
- Stage2 is only a secondary candidate for later A/B review.
- 이유:
  - Overall mAP50-95: Stage1 `0.730`, Stage2 `0.726`.
  - `damaged_tactile_block`: Stage1 precision/mAP50-95가 더 높음.
  - `tactile_damage_area`: Stage2 recall이 약간 높지만 precision과 mAP가 낮고, 전체 성능 개선으로 이어지지 않음.

## 3. `tactile_damage_area` error/signal review

Confirmed signals from existing artifacts:

- `normal_tactile_block`, `damaged_tactile_block` are strong classes.
  - Both stages show roughly `0.96+` mAP50 on those two classes.
- `tactile_damage_area` is the main bottleneck.
  - Stage1 test: P `0.644`, R `0.467`, mAP50 `0.528`, mAP50-95 `0.293`.
  - Stage2 test: P `0.602`, R `0.490`, mAP50 `0.525`, mAP50-95 `0.291`.
- Normalized confusion matrices show many `tactile_damage_area` misses into background and many background-origin predictions as `tactile_damage_area`.
- PR/F1/Recall curves show `tactile_damage_area` recall falls quickly as confidence threshold increases.
- Saved validation batch samples show `tactile_damage_area` boxes are small and often low-to-mid confidence; some predictions appear inside/near larger damaged block regions or on ambiguous tile texture.

Interpretation:

- 현재 모델은 “블록 단위 손상 여부”는 꽤 잘 잡지만, “손상 부위 작은 영역”은 위치/크기/라벨 경계가 어렵다.
- `tactile_damage_area`는 단순히 confidence threshold를 올리면 recall이 더 떨어지고, 낮추면 false positive가 늘어날 가능성이 높다.
- 따라서 자동 신고에는 model threshold 하나만 쓰기보다 per-class threshold + 중복/쿨다운 + 위치 기반 병합/검토 상태가 필요하다.

Not done yet:

- Per-image exhaustive false-positive/false-negative labeling.
- 새로운 validation split 재평가.
- 실제 앱 카메라 프레임에서의 현장 threshold calibration.

## 4. Threshold candidates

These are candidates for integration/calibration, not final production thresholds.
최종값은 test set을 반복 튜닝하지 말고 별도 calibration/validation 샘플에서 확정해야 한다.

### Candidate model choice

| Purpose | Candidate |
| --- | --- |
| Default app/MVP model | Stage1 `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/best.pt` |
| Secondary model for later comparison | Stage2 `runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521/weights/best.pt` |

### Candidate confidence policy

| Use | Class | Candidate confidence | Additional gate | Notes |
| --- | --- | ---: | --- | --- |
| Debug/display only | `normal_tactile_block` | `>= 0.40` | Never auto-report | 정상 블록은 신고 대상 아님. |
| Auto report | `damaged_tactile_block` | `0.30 ~ 0.40` | Location duplicate/cooldown; optionally require short persistence | 강한 클래스라 MVP 자동 신고 주력 후보. |
| Auto report | `tactile_damage_area` | `0.15 ~ 0.25` | Strongly require duplicate/cooldown and preferably nearby/overlapping damage context or review queue | recall 보전을 위해 낮게 보되, false positive 위험이 큼. |
| Global detector floor | all damage report candidates | `~0.15` | Per-class policy decides final action | damage area 후보를 버리지 않기 위한 하한. |
| Balanced all-class F1 reference | Stage1 all classes | `~0.28` | Reference only | Stage1 F1 curve의 all-class peak label 기준. |
| Balanced all-class F1 reference | Stage2 all classes | `~0.223` | Reference only | Stage2 F1 curve의 all-class peak label 기준. |

### Recommended MVP reporting behavior

- 자동 신고 대상은 현재 정책대로 tile damage 계열만 유지한다.
  - `damaged_tactile_block`
  - `tactile_damage_area`
- 일반 객체는 신고하지 않는다.
- 자동 신고 성공/실패는 사용자에게 굳이 음성 안내하지 않는다.
- 사용자가 음성으로 “신고해줘”라고 명시 요청한 경우는 우선순위를 높이고, 요청 처리 완료/실패는 짧게 TTS로 말한다.
- `tactile_damage_area` 단독 저신뢰 후보는 바로 확정 신고보다 “자동 수집/검토 필요” 상태로 남기는 편이 안전하다. 단, `damaged_tactile_block`과 함께 잡히거나 반복 프레임/위치 중복으로 확인되면 자동 신고 확정 후보가 될 수 있다.

## Next implementation candidates

1. Backend/app에 Stage1 `best.pt`를 v2 detector adapter 후보로 연결한다.
2. Per-class threshold config를 추가한다.
3. Auto report pipeline에서 `normal_tactile_block`은 신고 제외하고, damage classes만 정책 엔진에 통과시킨다.
4. `tactile_damage_area` false-positive/false-negative sample pack을 별도로 뽑아 라벨/경계/작은 객체 이슈를 리뷰한다.
