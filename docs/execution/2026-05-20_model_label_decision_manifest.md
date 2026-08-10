# 2026-05-20 v3 label/review decision manifest

## 범위

- 입력: `data_sources/manifests/walksafe_kr_v3_review_queue_2026-05-20.csv`
- 출력: `data_sources/manifests/walksafe_kr_v3_label_decision_manifest_2026-05-20.csv`
- 총 행 수: 196
- 실제 이미지/라벨 파일은 수정하지 않았다.
- `pred_box_xywhn`은 모델 예측 참고값이며 라벨로 사용하면 안 된다. CSV의 `label_decision_notes`에도 동일 주의를 남겼다.

## 적용 규칙

- 기존 v3 `hard_negative_review` 중 `v3_include_decision=include_candidate`는 `empty_label_candidate`로 두되, privacy/location 및 split-group audit 전까지 `blocked_by_privacy_split_audit`로 막았다.
- `box_relabel_review` 12행은 `manual_bbox_relabel_required`, `blocked_by_relabel`로 막았다.
- `min_box_policy_review` 9행은 `hold_min_box_policy`, `blocked_by_min_box_policy`로 막았다.
- `positive_review`는 `manual_positive_review_required`로 두었다. 이 중 정책상 후보 3행은 privacy/split gate만 남은 후보로 분리했고, 나머지 20행은 수동 positive 검토 전까지 막았다.
- `manual_review`는 `manual_duplicate_or_box_review_required`로 두었다.
- `hold`는 `hold_small_or_far_policy`로 두었다.
- 위 규칙에 직접 명시되지 않은 sampling 기반 `hard_negative_review` 18행은 초안상 `manual_hard_negative_review_required` / `blocked_by_manual_hard_negative_review`로 분리했다.

## v3_label_action count

| v3_label_action | rows |
| --- | ---: |
| `hold_small_or_far_policy` | 50 |
| `manual_duplicate_or_box_review_required` | 47 |
| `empty_label_candidate` | 37 |
| `manual_positive_review_required` | 23 |
| `manual_hard_negative_review_required` | 18 |
| `manual_bbox_relabel_required` | 12 |
| `hold_min_box_policy` | 9 |
| **total** | **196** |

## final_gate_status count

| final_gate_status | rows |
| --- | ---: |
| `blocked_by_small_or_far_policy` | 50 |
| `blocked_by_manual_duplicate_or_box_review` | 47 |
| `blocked_by_privacy_split_audit` | 40 |
| `blocked_by_manual_positive_review` | 20 |
| `blocked_by_manual_hard_negative_review` | 18 |
| `blocked_by_relabel` | 12 |
| `blocked_by_min_box_policy` | 9 |
| **total** | **196** |

## 지금 당장 학습 가능한 행이 사실상 0인 이유

현재 196행 모두 최소 하나 이상의 gate에 막혀 있다.

- 196행 모두 `needs_privacy_audit=yes`, `needs_split_group=yes`라 privacy/location audit과 split-group 확인이 끝나지 않았다.
- 12행은 bbox 수동 보정 전까지 학습에 넣을 수 없다. 특히 `pred_box_xywhn`은 세로 strip 전체에 가까운 모델 예측값이므로 라벨로 복사하면 안 된다.
- 9행은 min-box 정책 확정 전 보류 대상이다.
- 50행은 small/far-object 정책상 hold 대상이다.
- 47행은 duplicate/extra box 또는 박스 과다 여부를 수동으로 구분해야 한다.
- 20행은 positive 편입 여부를 수동 확인해야 한다.
- 18행은 sampling 기반 hard-negative 후보라 실제 정상 장면 여부를 수동 확인해야 한다.

단, 정책상 후보 40행은 별도로 분리된다. 이 40행은 `v3_include_decision=include_candidate`이며, 구성은 `empty_label_candidate` 37행과 small-object positive 후보 3행이다. 이들은 현재도 바로 학습 가능하다고 보지 않고 `blocked_by_privacy_split_audit`로 막았지만, privacy/location audit과 split-group gate를 통과하면 반영 후보가 될 수 있다.

## 검증 메모

- 출력 CSV row count: 196
- `v3_label_action` / `final_gate_status`별 count 합계: 각각 196
- `git diff --check`: PASS
