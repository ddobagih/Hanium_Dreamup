# tactile_damage_area 외부 AI 검수 가이드 (C안)

Date: 2026-05-22 KST

## 목적

`tactile_damage_area`(점자블록 파손/깨짐 영역) 모델의 오류 후보 120건을 외부 AI에게 **전체 재검수(C안)** 시키기 위한 GitHub 공유용 가이드다.

현재 로컬 AI 검수 결과는 참고용 제안일 뿐이며, 아직 최종 라벨 결정으로 적용하지 않았다.

## 검수 대상

- 클래스: `tactile_damage_area`
- 범위: val split 오류 후보 120건 전체
- 기준 모델: `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/best.pt`
- 기준 데이터셋: `datasets/walksafe_kr_tactile_3class_20260521`
- 목표: 기존 라벨 유지/수정/제거/추가/제외 여부를 독립적으로 판단

## 먼저 봐야 할 파일

1. 이 문서
2. 리뷰 이미지 README
   - `ai_tasks/walksafe_tactile_damage_area_review_20260522/README.md`
3. 전체 이미지 contact sheet
   - `ai_tasks/walksafe_tactile_damage_area_review_20260522/contact_sheets/full/`
4. crop contact sheet
   - `ai_tasks/walksafe_tactile_damage_area_review_20260522/contact_sheets/crops/`
5. 외부 검수용 CSV
   - `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_with_ai_suggestions_2026-05-22.csv`

## 이미지 보는 법

- 전체 120건은 `review_order` 1~120 순서다.
- contact sheet는 15장이고, 한 장당 8건이다.
  - sheet 01: review_order 1~8
  - sheet 02: review_order 9~16
  - ...
  - sheet 15: review_order 113~120
- 전체 contact sheet로 장면과 주변 맥락을 본다.
- crop contact sheet로 파손 영역 후보를 확대해서 본다.

### 박스 색상

- Yellow: 기존 GT `tactile_damage_area`
- Red: 모델 예측 `tactile_damage_area`
- Green: GT와 예측이 IoU 기준으로 매칭된 `tactile_damage_area`
- Cyan: 맥락용 GT `damaged_tactile_block`

주의: Red 박스는 모델 예측일 뿐이다. Red를 그대로 정답 라벨로 복사하면 안 된다.

## 참고용 CSV

- 오류 후보 원본 큐
  - `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_error_review_queue_2026-05-22.csv`
- 로컬 AI 제안
  - `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_ai_suggestions_2026-05-22.csv`
- 로컬 AI 제안 요약
  - `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_ai_suggestions_summary_2026-05-22.json`
- 제안 컬럼이 합쳐진 검수 템플릿
  - `ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_with_ai_suggestions_2026-05-22.csv`

로컬 AI 제안은 `ai_suggested_decision`, `ai_confidence`, `ai_reason_short` 컬럼에 들어 있다. 이 값은 최종 결정이 아니므로 반드시 이미지 기준으로 다시 판단한다.

## 최종 판단 옵션

외부 AI는 각 row에 대해 아래 중 하나를 선택한다.

| decision | 의미 |
| --- | --- |
| `accept_existing_labels` | 현재 GT `tactile_damage_area` 라벨이 적절함 |
| `fix_tactile_damage_area_bbox` | 파손 영역은 맞지만 bbox 위치/크기 수정 필요 |
| `remove_false_damage_area_label` | 현재 GT `tactile_damage_area` 라벨이 오탐/잘못된 라벨이라 제거 필요 |
| `add_missing_tactile_damage_area` | 파손 영역이 있는데 GT `tactile_damage_area` 라벨이 누락됨 |
| `exclude_unclear` | 이미지/라벨 판단이 불명확해서 학습 반영에서 제외 권장 |

## 외부 AI 산출물 형식

외부 AI는 새 CSV를 만들어 아래 컬럼을 채운다.

```csv
review_id,review_order,final_reviewer_decision,reviewer_confidence,reviewer_reason,manual_damage_area_boxes_xywhn,confirm_remove_all_damage_area
```

### 컬럼 규칙

- `review_id`: 원본 CSV의 `review_id` 그대로 사용
- `review_order`: 1~120 순서 그대로 사용
- `final_reviewer_decision`: 위 decision 중 하나
- `reviewer_confidence`: `high`, `medium`, `low` 중 하나
- `reviewer_reason`: 짧은 판단 근거
- `manual_damage_area_boxes_xywhn`:
  - bbox를 수정/추가할 때만 작성
  - YOLO normalized xywh 형식 사용
  - 예: `2:0.5123,0.6234,0.2100,0.0800`
  - 여러 박스는 세미콜론으로 구분
  - 예: `2:0.5123,0.6234,0.2100,0.0800;2:0.2200,0.7100,0.1200,0.0500`
- `confirm_remove_all_damage_area`:
  - `remove_false_damage_area_label`일 때만 `yes`
  - 그 외에는 빈 값 또는 `no`

bbox 좌표를 정확히 낼 수 없으면 `manual_damage_area_boxes_xywhn`을 억지로 만들지 않는다. 그 경우 `reviewer_reason`에 `manual_bbox_required`를 적고, 최종 반영 전에 사람이 좌표를 확정해야 한다.

## 권장 검수 절차

1. `contact_sheets/full`에서 review_order 순서대로 전체 장면을 본다.
2. 같은 번호의 `contact_sheets/crops`에서 후보 영역을 확대 확인한다.
3. CSV의 기존 GT/예측 개수와 로컬 AI 제안을 비교한다.
4. 로컬 AI 제안을 그대로 복사하지 말고 이미지 기준으로 최종 decision을 정한다.
5. 120건 전체를 채운 외부 AI 결과 CSV를 만든다.
6. 사람이 다시 빠르게 spot-check한 뒤 공식 템플릿의 `review_decision`에 반영한다.
7. 최종 반영이 확정된 뒤에만 아래 스크립트를 실행한다.

```bash
.venv/bin/python data_sources/scripts/apply_tactile_damage_area_review_decisions.py --build
```

## GitHub에 올릴 의도 범위

외부 검수에 필요한 최소 산출물만 올리는 것을 권장한다.

올릴 것:

- 이 가이드 문서
- review package README
- 15장 전체 contact sheet
- 15장 crop contact sheet
- 120건 review CSV / AI suggestion CSV / summary JSON
- 최종 결정 적용용 스크립트와 사용 문서

올리지 않을 것:

- 원본 데이터셋 이미지 전체
- 학습 weights/checkpoints
- `final_overlays/`, `final_crops/` 전체 원본 이미지 폴더
- chunk별 대량 중간 산출물
- GPU 실행 로그/캐시/임시 파일

## 현재 상태

- 120건 오류 후보 생성 완료
- contact sheet/crop contact sheet 생성 완료
- 로컬 AI-assisted visual pass 완료
- 최종 `review_decision`은 아직 비어 있음
- reviewed dataset은 아직 빌드하지 않음
- 추가 학습은 아직 진행하지 않음
