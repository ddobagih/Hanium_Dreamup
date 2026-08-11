#!/usr/bin/env python3
"""Apply reviewed tactile_damage_area decisions to a new dataset copy.

This script is conservative by default. If decisions are incomplete, it writes a
pending summary and exits without modifying/building a dataset. Use --build only
after the decision template has been filled and validated.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
from collections import Counter
from pathlib import Path

DATE = "2026-05-22"
ROOT = Path(".")
DEFAULT_SOURCE_DATASET = ROOT / "datasets/walksafe_kr_tactile_3class_20260521"
DEFAULT_TARGET_DATASET = ROOT / "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522"
DEFAULT_DECISIONS = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_template_2026-05-22.csv"
DEFAULT_APPLIED = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_applied_2026-05-22.csv"
DEFAULT_BLOCKED = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_blocked_2026-05-22.csv"
DEFAULT_MATERIALIZED = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_reviewed_materialized_manifest_2026-05-22.csv"
DEFAULT_SUMMARY = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/manifests/walksafe_tactile3_damage_area_review_decision_apply_summary_2026-05-22.json"
DEFAULT_DOC = ROOT / "ai_tasks/walksafe_tactile_damage_area_review_20260522/notes/review_apply.md"

ALLOWED_DECISIONS = {
    "accept_existing_labels",
    "fix_tactile_damage_area_bbox",
    "remove_false_damage_area_label",
    "add_missing_tactile_damage_area",
    "exclude_unclear",
}
CLASS_NAMES = {
    0: "normal_tactile_block",
    1: "damaged_tactile_block",
    2: "tactile_damage_area",
}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Apply tactile_damage_area review decisions.")
    parser.add_argument("--source-dataset", default=str(DEFAULT_SOURCE_DATASET))
    parser.add_argument("--target-dataset", default=str(DEFAULT_TARGET_DATASET))
    parser.add_argument("--decisions", default=str(DEFAULT_DECISIONS))
    parser.add_argument("--applied-out", default=str(DEFAULT_APPLIED))
    parser.add_argument("--blocked-out", default=str(DEFAULT_BLOCKED))
    parser.add_argument("--materialized-out", default=str(DEFAULT_MATERIALIZED))
    parser.add_argument("--summary-out", default=str(DEFAULT_SUMMARY))
    parser.add_argument("--doc-out", default=str(DEFAULT_DOC))
    parser.add_argument("--build", action="store_true", help="Materialize the reviewed dataset if all decisions are complete and valid.")
    parser.add_argument("--copy-images", action="store_true", help="Copy images instead of creating relative symlinks.")
    parser.add_argument("--reset", action="store_true", help="Remove the target dataset before materialization.")
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


def parse_label(path: Path) -> list[list[str]]:
    if not path.exists():
        return []
    rows: list[list[str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.strip().split()
        if len(parts) >= 5:
            rows.append(parts[:5])
    return rows


def format_label(rows: list[list[str]]) -> str:
    lines = [" ".join(row) for row in rows]
    return "\n".join(lines) + ("\n" if lines else "")


def parse_manual_boxes(value: str) -> list[list[str]]:
    value = (value or "").strip()
    if not value:
        return []
    boxes: list[list[str]] = []
    for raw_item in value.replace("|", ";").split(";"):
        item = raw_item.strip()
        if not item:
            continue
        if ":" in item:
            class_part, coords_part = item.split(":", 1)
        else:
            class_part, coords_part = "2", item
        try:
            class_id = int(class_part.strip())
        except ValueError as exc:
            raise ValueError(f"invalid class id in manual box: {item}") from exc
        if class_id != 2:
            raise ValueError(f"manual damage-area boxes must use class id 2: {item}")
        coords = [coord.strip() for coord in coords_part.split(",")]
        if len(coords) != 4:
            raise ValueError(f"manual box must have 4 coordinates: {item}")
        parsed: list[str] = []
        for coord in coords:
            number = float(coord)
            if number < 0 or number > 1:
                raise ValueError(f"manual box coordinate outside [0,1]: {item}")
            parsed.append(f"{number:.6f}")
        boxes.append(["2", *parsed])
    return boxes


def validate_decisions(rows: list[dict[str, str]]) -> tuple[list[dict[str, object]], list[str], Counter]:
    applied_rows: list[dict[str, object]] = []
    errors: list[str] = []
    status_counts: Counter = Counter()
    seen_review_ids: set[str] = set()

    for row in rows:
        review_id = row.get("review_id", "")
        decision = row.get("review_decision", "").strip()
        applied = dict(row)
        applied["validation_status"] = "ok"
        applied["validation_reason"] = ""

        if not review_id:
            applied["validation_status"] = "blocked"
            applied["validation_reason"] = "missing review_id"
        elif review_id in seen_review_ids:
            applied["validation_status"] = "blocked"
            applied["validation_reason"] = "duplicate review_id"
        elif not decision:
            applied["validation_status"] = "pending"
            applied["validation_reason"] = "review_decision is blank"
        elif decision not in ALLOWED_DECISIONS:
            applied["validation_status"] = "blocked"
            applied["validation_reason"] = f"invalid review_decision: {decision}"
        else:
            manual_boxes = row.get("manual_damage_area_boxes_xywhn", "")
            if decision in {"fix_tactile_damage_area_bbox", "add_missing_tactile_damage_area"}:
                try:
                    boxes = parse_manual_boxes(manual_boxes)
                except ValueError as exc:
                    applied["validation_status"] = "blocked"
                    applied["validation_reason"] = str(exc)
                else:
                    if not boxes:
                        applied["validation_status"] = "blocked"
                        applied["validation_reason"] = "manual_damage_area_boxes_xywhn is required"
            if decision == "remove_false_damage_area_label" and row.get("confirm_remove_all_damage_area", "").strip().lower() != "yes":
                applied["validation_status"] = "blocked"
                applied["validation_reason"] = "confirm_remove_all_damage_area=yes is required"

        seen_review_ids.add(review_id)
        status_counts[applied["validation_status"]] += 1
        if applied["validation_status"] in {"blocked", "pending"}:
            errors.append(f"{review_id}: {applied['validation_reason']}")
        applied_rows.append(applied)
    return applied_rows, errors, status_counts


def image_rel_split(path: Path, source_dataset: Path) -> tuple[str, str]:
    try:
        rel = path.resolve().relative_to(source_dataset.resolve())
    except ValueError as exc:
        raise ValueError(f"image path is outside source dataset: {path}") from exc
    parts = rel.parts
    if len(parts) < 3 or parts[0] != "images":
        raise ValueError(f"unexpected image path under dataset: {path}")
    return parts[1], parts[-1]


def link_or_copy(source: Path, target: Path, copy: bool) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() or target.is_symlink():
        target.unlink()
    if copy:
        shutil.copy2(source, target)
        return
    rel = os.path.relpath(source.resolve(), start=target.parent.resolve())
    target.symlink_to(rel)


def write_data_yaml(target_dataset: Path) -> None:
    names = "\n".join(f"  {idx}: {name}" for idx, name in CLASS_NAMES.items())
    target_dataset.mkdir(parents=True, exist_ok=True)
    (target_dataset / "data.yaml").write_text(
        f"path: {target_dataset}\ntrain: images/train\nval: images/val\ntest: images/test\n\nnames:\n{names}\n",
        encoding="utf-8",
    )


def adjusted_label_rows(label_rows: list[list[str]], decision: str, manual_boxes: list[list[str]]) -> list[list[str]]:
    if decision in {"accept_existing_labels", "exclude_unclear"}:
        return label_rows
    non_damage_area = [row for row in label_rows if row and row[0] != "2"]
    existing_damage_area = [row for row in label_rows if row and row[0] == "2"]
    if decision == "fix_tactile_damage_area_bbox":
        return non_damage_area + manual_boxes
    if decision == "remove_false_damage_area_label":
        return non_damage_area
    if decision == "add_missing_tactile_damage_area":
        return non_damage_area + existing_damage_area + manual_boxes
    raise ValueError(f"unhandled decision: {decision}")


def materialize_dataset(args: argparse.Namespace, decisions: list[dict[str, str]]) -> list[dict[str, object]]:
    source_dataset = Path(args.source_dataset)
    target_dataset = Path(args.target_dataset)
    if args.reset and target_dataset.exists():
        shutil.rmtree(target_dataset)
    write_data_yaml(target_dataset)

    decisions_by_image = {row["image_path"]: row for row in decisions if row.get("review_decision")}
    excluded_images = {
        row["image_path"] for row in decisions if row.get("review_decision") == "exclude_unclear"
    }
    manifest_rows: list[dict[str, object]] = []

    for split in ("train", "val", "test"):
        image_dir = source_dataset / "images" / split
        if not image_dir.exists():
            continue
        for source_image in sorted(path for path in image_dir.iterdir() if path.suffix.lower() in IMAGE_EXTENSIONS):
            source_image_str = str(source_image)
            if source_image_str in excluded_images:
                manifest_rows.append(
                    {
                        "split": split,
                        "source_image": source_image,
                        "target_image": "",
                        "target_label": "",
                        "review_decision": "exclude_unclear",
                        "label_action": "excluded",
                    }
                )
                continue

            source_label = source_dataset / "labels" / split / f"{source_image.stem}.txt"
            target_image = target_dataset / "images" / split / source_image.name
            target_label = target_dataset / "labels" / split / f"{source_image.stem}.txt"
            label_rows = parse_label(source_label)
            decision_row = decisions_by_image.get(source_image_str)
            label_action = "copied"
            decision = ""
            if decision_row:
                decision = decision_row["review_decision"]
                manual_boxes = parse_manual_boxes(decision_row.get("manual_damage_area_boxes_xywhn", ""))
                label_rows = adjusted_label_rows(label_rows, decision, manual_boxes)
                label_action = decision

            link_or_copy(source_image, target_image, args.copy_images)
            target_label.parent.mkdir(parents=True, exist_ok=True)
            target_label.write_text(format_label(label_rows), encoding="utf-8")
            manifest_rows.append(
                {
                    "split": split,
                    "source_image": source_image,
                    "source_label": source_label,
                    "target_image": target_image,
                    "target_label": target_label,
                    "review_decision": decision,
                    "label_action": label_action,
                    "class2_box_count": sum(1 for row in label_rows if row and row[0] == "2"),
                    "total_box_count": len(label_rows),
                }
            )
    return manifest_rows


def write_summary_doc(args: argparse.Namespace, summary: dict) -> None:
    Path(args.summary_out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.summary_out).write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    Path(args.doc_out).parent.mkdir(parents=True, exist_ok=True)
    status = summary["status"]
    Path(args.doc_out).write_text(
        f"""# tactile_damage_area review decision apply

Date: {DATE} KST

## Status

`{status}`

## Inputs

- Source dataset: `{args.source_dataset}`
- Decision CSV: `{args.decisions}`
- Target dataset: `{args.target_dataset}`

## Summary

```json
{json.dumps(summary, ensure_ascii=False, indent=2)}
```

## Notes

- If status is `pending_decisions`, fill `review_decision` in the decision template first.
- `fix_tactile_damage_area_bbox` and `add_missing_tactile_damage_area` require `manual_damage_area_boxes_xywhn`.
- `remove_false_damage_area_label` requires `confirm_remove_all_damage_area=yes`.
- This script does not train a model.
""",
        encoding="utf-8",
    )


def main() -> int:
    args = parse_args()
    decision_rows = read_csv(Path(args.decisions))
    applied_rows, errors, status_counts = validate_decisions(decision_rows)
    write_csv(Path(args.applied_out), applied_rows, list(applied_rows[0].keys()) if applied_rows else [])
    blocked_rows = [row for row in applied_rows if row.get("validation_status") in {"pending", "blocked"}]
    if blocked_rows:
        write_csv(Path(args.blocked_out), blocked_rows, list(blocked_rows[0].keys()))

    summary = {
        "date": DATE,
        "status": "pending_decisions" if errors else "ready_to_build",
        "source_dataset": args.source_dataset,
        "target_dataset": args.target_dataset,
        "decision_csv": args.decisions,
        "rows": len(decision_rows),
        "validation_status_counts": dict(status_counts),
        "decision_counts": dict(Counter(row.get("review_decision", "") or "blank" for row in decision_rows)),
        "applied_rows": args.applied_out,
        "blocked_rows": args.blocked_out if blocked_rows else "",
        "materialized_manifest": "",
        "built_dataset": False,
        "errors_preview": errors[:20],
    }

    if errors:
        write_summary_doc(args, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    if not args.build:
        summary["status"] = "ready_to_build_not_run"
        write_summary_doc(args, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    manifest_rows = materialize_dataset(args, decision_rows)
    write_csv(Path(args.materialized_out), manifest_rows, list(manifest_rows[0].keys()) if manifest_rows else [])
    summary.update(
        {
            "status": "built",
            "materialized_manifest": args.materialized_out,
            "built_dataset": True,
            "materialized_rows": len(manifest_rows),
            "materialized_action_counts": dict(Counter(str(row.get("label_action", "")) for row in manifest_rows)),
        }
    )
    write_summary_doc(args, summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
