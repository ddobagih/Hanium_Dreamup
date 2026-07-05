# 2026-05-20 Model sampling 200 triage summary

범위: `walksafe_kr_v2` test split 200장 failure sampling 산출물의 CSV/JSON 메타데이터만 요약했다. 이미지 복사, 열람, 저장, 새 학습, full sampling, 이미지 생성은 수행하지 않았다.

## 입력

- `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/failure_candidates.csv`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/summary.json`
- `docs/execution/2026-05-20_model_failure_sampling_200.md`

## 전체 요약

| 항목 | 값 |
| --- | ---: |
| candidate rows | 135 |
| summary candidate rows | 135 |
| sample images | 200 |
| positive / negative sample images | 100 / 100 |
| generated image files | 0 |

## bucket / action / privacy_review_required 요약

| bucket | rows | summary rows | events | action | privacy_review_required |
| --- | ---: | ---: | ---: | --- | --- |
| `false_positive_extra_box` | 47 | 47 | 47 | `add_as_hard_negative_and_check_threshold` 47 | `yes` 47 |
| `false_positive_normal_tactile` | 18 | 18 | 18 | `add_as_hard_negative_and_check_threshold` 18 | `yes` 18 |
| `missed_defect` | 20 | 20 | 20 | `review_missed_or_low_iou_defect` 20 | `yes` 20 |
| `small_or_far` | 50 | 50 | 124 | `review_small_object_policy` 50 | `yes` 50 |

참고: `small_or_far`는 event 124건 중 sampler bucket limit으로 50행만 CSV에 기록됐다.

## bucket별 상위 confidence 후보 5개

### `false_positive_extra_box`

| rank | image file | confidence | max_iou | gt_area | action | privacy_review_required |
| ---: | --- | ---: | ---: | ---: | --- | --- |
| 98 | `aihub513_tactile_2_09_1_1_1_2_20210829_0000386812.jpg` | 0.753533 | 0.054258 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 43 | `aihub513_tactile_2_09_1_1_1_1_20210902_0000446087.jpg` | 0.552764 | 0.467439 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 132 | `aihub513_tactile_2_09_1_1_6_1_20210909_0000493313.jpg` | 0.537201 | 0.437405 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 31 | `aihub513_tactile_2_09_1_1_1_1_20210728_0000166628.jpg` | 0.529218 | 0.013379 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 133 | `aihub513_tactile_2_09_1_1_6_1_20210909_0000493313.jpg` | 0.495869 | 0.097562 | - | `add_as_hard_negative_and_check_threshold` | `yes` |

### `false_positive_normal_tactile`

| rank | image file | confidence | max_iou | gt_area | action | privacy_review_required |
| ---: | --- | ---: | ---: | ---: | --- | --- |
| 6 | `aihub513_tactile_2_09_0_1_1_2_20210728_0000022477.jpg` | 0.922097 | 0.000000 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 14 | `aihub513_tactile_2_09_0_1_4_1_20210928_0000569475.jpg` | 0.902657 | 0.000000 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 11 | `aihub513_tactile_2_09_0_1_4_1_20210917_0000516041.jpg` | 0.840786 | 0.000000 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 2 | `aihub513_tactile_2_09_0_1_1_1_20210923_0000534043.jpg` | 0.807652 | 0.000000 | - | `add_as_hard_negative_and_check_threshold` | `yes` |
| 17 | `aihub513_tactile_2_09_0_1_5_1_20210924_0000542535.jpg` | 0.761937 | 0.000000 | - | `add_as_hard_negative_and_check_threshold` | `yes` |

### `missed_defect`

| rank | image file | confidence | max_iou | gt_area | action | privacy_review_required |
| ---: | --- | ---: | ---: | ---: | --- | --- |
| 123 | `aihub513_tactile_2_09_1_1_5_1_20211022_0000687712.jpg` | 0.945582 | 0.176758 | 0.073651 | `review_missed_or_low_iou_defect` | `yes` |
| 105 | `aihub513_tactile_2_09_1_1_1_4_20211019_0000657403.jpg` | 0.934095 | 0.037995 | 0.017101 | `review_missed_or_low_iou_defect` | `yes` |
| 50 | `aihub513_tactile_2_09_1_1_1_1_20210930_0000584177.jpg` | 0.931651 | 0.041210 | 0.017771 | `review_missed_or_low_iou_defect` | `yes` |
| 129 | `aihub513_tactile_2_09_1_1_5_2_20211022_0000687614.jpg` | 0.929569 | 0.050445 | 0.020842 | `review_missed_or_low_iou_defect` | `yes` |
| 61 | `aihub513_tactile_2_09_1_1_1_1_20211021_0000700279.jpg` | 0.917167 | 0.215460 | 0.083586 | `review_missed_or_low_iou_defect` | `yes` |

### `small_or_far`

| rank | image file | confidence | max_iou | gt_area | action | privacy_review_required |
| ---: | --- | ---: | ---: | ---: | --- | --- |
| 56 | `aihub513_tactile_2_09_1_1_1_1_20211007_0000602760.jpg` | 0.952321 | 0.015917 | 0.005887 | `review_small_object_policy` | `yes` |
| 53 | `aihub513_tactile_2_09_1_1_1_1_20211004_0000595930.jpg` | 0.948810 | 0.007731 | 0.003059 | `review_small_object_policy` | `yes` |
| 37 | `aihub513_tactile_2_09_1_1_1_1_20210811_0000158622.jpg` | 0.946978 | 0.003222 | 0.001402 | `review_small_object_policy` | `yes` |
| 86 | `aihub513_tactile_2_09_1_1_1_2_20210806_0000105871.jpeg` | 0.946741 | 0.003022 | 0.001406 | `review_small_object_policy` | `yes` |
| 87 | `aihub513_tactile_2_09_1_1_1_2_20210806_0000105871.jpeg` | 0.946741 | 0.000118 | 0.000055 | `review_small_object_policy` | `yes` |

## 수동 triage 기준

- `false_positive_normal_tactile`: 정상 점자블록을 결함으로 예측한 hard negative 후보로 본다. privacy/location audit 후 실제 정상 장면이면 hard negative 편입을 검토하고, threshold 영향도 함께 확인한다.
- `false_positive_extra_box`: GT와 낮거나 애매한 IoU로 잡힌 중복/박스 과다 후보로 본다. 같은 결함 주변의 duplicate box인지, 실제 누락 GT인지 수동 확인이 필요하다.
- `missed_defect`: positive 추가 또는 bbox 보정 후보로 본다. GT 누락/오표기인지, 예측 box와 GT의 위치 차이인지 확인한 뒤 라벨 보정 여부를 결정한다.
- `small_or_far`: 작은/먼 결함 후보는 min-box 정책 확정 전 hold한다. 정책 확정 전에는 positive로 바로 편입하지 않는다.

## v3 학습에 바로 넣지 말아야 하는 이유

- 모든 후보가 `privacy_review_required=yes`이며, 자동 sampler 후보라 수동 시각 검수와 privacy/location audit이 아직 끝나지 않았다.
- `false_positive_extra_box`는 duplicate/과다 박스와 실제 라벨 누락을 구분해야 해서 그대로 넣으면 라벨 노이즈가 생길 수 있다.
- `missed_defect`는 positive 편입과 bbox 보정 중 판단이 필요해 원본 라벨 기준을 먼저 정해야 한다.
- `small_or_far`는 124 event 중 50행만 기록됐고 min-box 정책 전 hold 대상이라, 정책 없이 학습에 넣으면 작은 객체 기준이 흔들릴 수 있다.
- 따라서 v3에는 수동 triage, privacy review, min-box 정책 확정 후 선별 반영한다.

## 검증

- Python `csv/json`: `failure_candidates.csv` rows = 135, `summary.json` `candidate_rows_written` = 135 확인.
- Python `csv/json`: bucket별 CSV count가 summary `candidate_rows_by_bucket`와 일치함을 확인.
- `git diff --check`: PASS.
