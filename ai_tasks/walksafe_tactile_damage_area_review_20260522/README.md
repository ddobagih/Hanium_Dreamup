# tactile_damage_area external review package

GitHub에서 외부 AI가 `tactile_damage_area` 오류 후보 120건을 C-mode로 전체 검수하기 위한 self-contained 패키지다.

## Start here

1. `GUIDE.md`를 먼저 읽는다.
2. `contact_sheets/full/`에서 전체 장면을 본다.
3. `contact_sheets/crops/`에서 같은 review_order의 crop을 확인한다.
4. `manifests/walksafe_tactile3_damage_area_review_decision_template_with_ai_suggestions_2026-05-22.csv`를 기준으로 결과 CSV를 만든다.

## Folder layout

```text
ai_tasks/walksafe_tactile_damage_area_review_20260522/
  GUIDE.md
  README.md
  TASK_PROMPT_FOR_AI.md
  contact_sheets/
    full/   # 15 sheets, 8 rows per sheet
    crops/  # 15 crop sheets, same review_order mapping
  manifests/
    *.csv   # review queue, blank decision template, AI suggestions
    *.json  # compact summaries
  notes/    # local execution notes and pending apply summary
```

## Review order mapping

- `damage_area_final_review_sheet_01.jpg` / `damage_area_final_crop_sheet_01.jpg`: review_order 1-8
- `damage_area_final_review_sheet_02.jpg` / `damage_area_final_crop_sheet_02.jpg`: review_order 9-16
- Continue the same pattern through sheet 15.
- `damage_area_final_review_sheet_15.jpg` / `damage_area_final_crop_sheet_15.jpg`: review_order 113-120

## Box colors

- Yellow: GT `tactile_damage_area`
- Red: predicted `tactile_damage_area`
- Green: matched `tactile_damage_area`
- Cyan: GT `damaged_tactile_block` context

## Important notes

- Local AI suggestions are non-final. Verify from the images.
- Do not copy red model prediction boxes blindly.
- Some CSV columns still include local-only `overlay_path` / `crop_path` values for traceability. Those full-size artifacts are intentionally not part of this GitHub package; use the contact sheets in this package for review.
- Full local `final_overlays/`, `final_crops/`, chunk artifacts, datasets, weights, and logs are not intended for GitHub.
