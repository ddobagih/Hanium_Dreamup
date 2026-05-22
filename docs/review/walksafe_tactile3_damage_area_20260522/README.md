# walksafe_tactile3_damage_area_20260522 review package

This folder contains the GitHub-shareable visual review package for the 120 selected `tactile_damage_area` error-review rows.

## Use these folders

- `final_contact_sheets/`: full-image contact sheets, 15 files, 8 rows per sheet.
- `final_crop_contact_sheets/`: crop contact sheets for the same 120 rows.

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

## Main guide

Read the full external-review guide before making decisions:

- `docs/execution/2026-05-22_tactile_damage_area_external_ai_review_guide.md`

## Not intended for GitHub

The local `final_overlays/`, `final_crops/`, and chunk-level visual artifacts are large and are not required for external AI review unless explicitly requested.
