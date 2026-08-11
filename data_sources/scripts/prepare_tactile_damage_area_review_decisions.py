#!/usr/bin/env python3
"""Prepare a human review decision template for tactile_damage_area error rows."""

from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from pathlib import Path

DATE = "2026-05-22"
ROOT = Path(".")
DEFAULT_QUEUE = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_error_review_queue_2026-05-22.csv"
DEFAULT_TEMPLATE = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_2026-05-22.csv"
DEFAULT_SUMMARY = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_summary_2026-05-22.json"
DEFAULT_DOC = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/notes/review_decision_workflow.md"

ALLOWED_DECISIONS = (
    "accept_existing_labels",
    "fix_tactile_damage_area_bbox",
    "remove_false_damage_area_label",
    "add_missing_tactile_damage_area",
    "exclude_unclear",
)

TEMPLATE_FIELDS = [
    "review_id",
    "review_order",
    "issue_type",
    "suggested_review_focus",
    "suggested_default_decision",
    "review_decision",
    "bbox_source",
    "manual_damage_area_boxes_xywhn",
    "confirm_remove_all_damage_area",
    "review_notes",
    "allowed_review_decisions",
    "bbox_format",
    "source_split",
    "image_path",
    "label_path",
    "overlay_path",
    "crop_path",
    "gt_damage_area_count",
    "pred_damage_area_count",
    "tp_damage_area_count",
    "fn_damage_area_count",
    "fp_damage_area_count",
    "gt_damage_area_boxes",
    "pred_damage_area_boxes",
    "fn_damage_area_boxes",
    "fp_damage_area_boxes",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create tactile_damage_area review decision template.")
    parser.add_argument("--queue", default=str(DEFAULT_QUEUE))
    parser.add_argument("--out", default=str(DEFAULT_TEMPLATE))
    parser.add_argument("--summary-out", default=str(DEFAULT_SUMMARY))
    parser.add_argument("--doc-out", default=str(DEFAULT_DOC))
    return parser.parse_args()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as file:
        return list(csv.DictReader(file))


def write_csv(path: Path, rows: list[dict[str, object]], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({field: row.get(field, "") for field in fieldnames})


def suggestion_for(issue_type: str) -> tuple[str, str]:
    if issue_type == "mixed_fn_fp_damage_area":
        return (
            "GT/prediction mismatch. Check whether yellow GT boxes are accurate or need bbox cleanup.",
            "fix_tactile_damage_area_bbox_if_label_boundary_is_wrong_else_accept_existing_labels",
        )
    if issue_type == "damage_block_ok_area_missed":
        return (
            "Model found damaged block context but missed damage-area boxes. Verify yellow GT damage-area boxes.",
            "accept_existing_labels_if_yellow_boxes_are_correct",
        )
    if issue_type == "false_negative_damage_area":
        return (
            "Model missed existing damage-area GT. Verify the yellow GT boxes are real damage areas.",
            "accept_existing_labels_if_yellow_boxes_are_correct",
        )
    if issue_type == "hard_negative_false_positive_damage_area":
        return (
            "No GT damage-area exists but model predicted red boxes. Check if red boxes are false positives or missing labels.",
            "accept_existing_labels_if_red_is_false_positive_else_add_missing_tactile_damage_area",
        )
    return ("Review manually.", "")


def main() -> int:
    args = parse_args()
    queue_path = Path(args.queue)
    rows = read_csv(queue_path)
    if not rows:
        raise SystemExit(f"empty queue: {queue_path}")

    template_rows: list[dict[str, object]] = []
    for row in rows:
        focus, suggested = suggestion_for(row.get("issue_type", ""))
        template_rows.append(
            {
                "review_id": row.get("review_id", ""),
                "review_order": row.get("review_order", ""),
                "issue_type": row.get("issue_type", ""),
                "suggested_review_focus": focus,
                "suggested_default_decision": suggested,
                "review_decision": "",
                "bbox_source": "",
                "manual_damage_area_boxes_xywhn": "",
                "confirm_remove_all_damage_area": "",
                "review_notes": "",
                "allowed_review_decisions": "|".join(ALLOWED_DECISIONS),
                "bbox_format": "class_id:x_center,y_center,width,height; use class_id 2 for tactile_damage_area; normalized YOLO coords",
                "source_split": row.get("source_split", ""),
                "image_path": row.get("image_path", ""),
                "label_path": row.get("label_path", ""),
                "overlay_path": row.get("overlay_path", ""),
                "crop_path": row.get("crop_path", ""),
                "gt_damage_area_count": row.get("gt_damage_area_count", ""),
                "pred_damage_area_count": row.get("pred_damage_area_count", ""),
                "tp_damage_area_count": row.get("tp_damage_area_count", ""),
                "fn_damage_area_count": row.get("fn_damage_area_count", ""),
                "fp_damage_area_count": row.get("fp_damage_area_count", ""),
                "gt_damage_area_boxes": row.get("gt_damage_area_boxes", ""),
                "pred_damage_area_boxes": row.get("pred_damage_area_boxes", ""),
                "fn_damage_area_boxes": row.get("fn_damage_area_boxes", ""),
                "fp_damage_area_boxes": row.get("fp_damage_area_boxes", ""),
            }
        )

    write_csv(Path(args.out), template_rows, TEMPLATE_FIELDS)
    issue_counts = Counter(row["issue_type"] for row in template_rows)
    summary = {
        "date": DATE,
        "source_queue": str(queue_path),
        "decision_template": args.out,
        "rows": len(template_rows),
        "issue_counts": dict(issue_counts),
        "allowed_decisions": list(ALLOWED_DECISIONS),
        "manual_box_field": "manual_damage_area_boxes_xywhn",
        "notes": [
            "review_decision is intentionally blank; suggestions are non-binding.",
            "Do not copy model prediction boxes into labels without visual confirmation.",
            "Rows needing bbox edits require manual YOLO-normalized class-2 boxes before dataset materialization.",
        ],
    }
    Path(args.summary_out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.summary_out).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    Path(args.doc_out).parent.mkdir(parents=True, exist_ok=True)
    issue_json = json.dumps(dict(issue_counts), ensure_ascii=False, indent=2)
    Path(args.doc_out).write_text(
        f"""# tactile_damage_area review decision workflow

Date: {DATE} KST

## Current status

The error review pack is generated, but label decisions are still pending. This document prepares the decision/application step; it does not finalize labels by itself.

## Inputs

- Review queue: `{queue_path}`
- Decision template: `{args.out}`
- Summary JSON: `{args.summary_out}`
- Review images/contact sheets: `ai_tasks/walksafe_tactile_damage_area_review_20260522/`

## Rows

```json
{issue_json}
```

## Decision options

Fill `review_decision` with one of:

- `accept_existing_labels`: existing YOLO label is usable; no label edit needed.
- `fix_tactile_damage_area_bbox`: replace class-2 `tactile_damage_area` boxes with `manual_damage_area_boxes_xywhn`.
- `remove_false_damage_area_label`: remove all class-2 boxes only when `confirm_remove_all_damage_area=yes`.
- `add_missing_tactile_damage_area`: append manually confirmed class-2 boxes from `manual_damage_area_boxes_xywhn`.
- `exclude_unclear`: keep this image out of the reviewed materialized dataset.

## Box format

`manual_damage_area_boxes_xywhn` uses semicolon-separated normalized YOLO boxes:

```text
2:x_center,y_center,width,height;2:x_center,y_center,width,height
```

Only class id `2` is accepted for manual damage-area boxes.

## Important guardrails

- Suggestions in `suggested_default_decision` are not final labels.
- Red prediction boxes are review aids only; do not copy them blindly.
- For `fix_tactile_damage_area_bbox` and `add_missing_tactile_damage_area`, manual boxes are required.
- For `remove_false_damage_area_label`, set `confirm_remove_all_damage_area=yes` to avoid accidental deletion.
- The current review pack is from the `val` split. It can improve validation label quality and reveal failure modes, but additional train-split review may still be needed before meaningful retraining.

## Apply command after decisions are filled

```bash
cd /home/ddobagi/Code/hanium-dreamup
.venv/bin/python data_sources/scripts/apply_tactile_damage_area_review_decisions.py --build
```
""",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
