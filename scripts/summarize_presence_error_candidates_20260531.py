#!/usr/bin/env python3
"""Summarize FP/FN candidates from saved image-level presence CSVs.

This does not need the original image dataset.  It reads the existing
per-image presence CSVs and saved YOLO prediction labels, then writes compact
FP/FN candidate lists for threshold review.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPORT_DIR = REPO_ROOT / "reports/runs/evaluations/stage1_yolo26s_reviewed_test_presence_20260523_203422"
DEFAULT_PRED_LABEL_DIR = REPO_ROOT / "runs/predict/stage1_yolo26s_reviewed_test_labels_20260523_203422/labels"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "reports/runs/evaluations/stage1_yolo26s_reviewed_test_presence_error_candidates_20260531"


@dataclass(frozen=True)
class ThresholdSummary:
    threshold: float
    total_images: int
    false_positive: int
    false_negative: int
    output_csv: str


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarize image-level presence FP/FN candidates.")
    parser.add_argument("--report-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--pred-label-dir", type=Path, default=DEFAULT_PRED_LABEL_DIR)
    parser.add_argument("--target-class-id", type=int, default=1)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--limit-per-outcome", type=int, default=200)
    return parser.parse_args()


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def threshold_from_name(path: Path) -> float:
    match = re.search(r"threshold_(\d+)_per_image\.csv$", path.name)
    if not match:
        raise ValueError(f"cannot parse threshold from file name: {path}")
    return int(match.group(1)) / 100


def target_scores(label_path: Path, target_class_id: int) -> list[float]:
    if not label_path.exists():
        return []
    scores: list[float] = []
    for raw_line in label_path.read_text(encoding="utf-8").splitlines():
        parts = raw_line.split()
        if len(parts) < 6:
            continue
        try:
            class_id = int(float(parts[0]))
            score = float(parts[5])
        except ValueError:
            continue
        if class_id == target_class_id:
            scores.append(score)
    return sorted(scores, reverse=True)


def bool_text(value: str) -> bool:
    return value.strip().lower() == "true"


def load_presence_rows(path: Path, pred_label_dir: Path, target_class_id: int) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            image_id = row["image_id"]
            scores = target_scores(pred_label_dir / f"{image_id}.txt", target_class_id)
            rows.append(
                {
                    "image_id": image_id,
                    "gt_positive": bool_text(row["gt_positive"]),
                    "pred_positive": bool_text(row["pred_positive"]),
                    "outcome": row["outcome"],
                    "target_prediction_count": len(scores),
                    "target_max_confidence": scores[0] if scores else None,
                    "target_top3_confidences": ";".join(f"{score:.6f}" for score in scores[:3]),
                }
            )
    return rows


def write_csv(path: Path, rows: Iterable[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "threshold",
        "image_id",
        "outcome",
        "gt_positive",
        "pred_positive",
        "target_prediction_count",
        "target_max_confidence",
        "target_top3_confidences",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def sort_candidates(rows: list[dict[str, object]], outcome: str, limit: int) -> list[dict[str, object]]:
    candidates = [row for row in rows if row["outcome"] == outcome]
    candidates.sort(
        key=lambda row: (
            -1.0 if row["target_max_confidence"] is None else -float(row["target_max_confidence"]),
            str(row["image_id"]),
        )
    )
    return candidates[:limit]


def write_markdown(path: Path, summaries: list[ThresholdSummary]) -> None:
    lines = [
        "# Stage1 presence FP/FN candidate summary",
        "",
        "Generated from saved per-image presence CSVs and saved YOLO prediction labels.",
        "No original images or GT label files were read.",
        "",
        "| threshold | total | FP | FN | candidate csv |",
        "|---:|---:|---:|---:|---|",
    ]
    for item in summaries:
        lines.append(
            f"| {item.threshold:.2f} | {item.total_images} | {item.false_positive} | "
            f"{item.false_negative} | `{item.output_csv}` |"
        )
    lines.append("")
    lines.append("Sort order: highest target-class prediction confidence first within each FP/FN outcome.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if args.limit_per_outcome < 1:
        raise SystemExit("--limit-per-outcome must be >= 1")
    presence_csvs = sorted(args.report_dir.glob("presence_threshold_*_per_image.csv"))
    if not presence_csvs:
        raise SystemExit(f"no per-image presence CSVs found in {args.report_dir}")
    if not args.pred_label_dir.exists():
        raise SystemExit(f"prediction label directory not found: {args.pred_label_dir}")

    summaries: list[ThresholdSummary] = []
    for presence_csv in presence_csvs:
        threshold = threshold_from_name(presence_csv)
        rows = load_presence_rows(presence_csv, args.pred_label_dir, args.target_class_id)
        candidates: list[dict[str, object]] = []
        for outcome in ("fp", "fn"):
            for row in sort_candidates(rows, outcome, args.limit_per_outcome):
                candidates.append({"threshold": f"{threshold:.2f}", **row})
        output_csv = args.output_dir / f"presence_threshold_{int(round(threshold * 100)):03d}_fp_fn_candidates.csv"
        write_csv(output_csv, candidates)
        summaries.append(
            ThresholdSummary(
                threshold=threshold,
                total_images=len(rows),
                false_positive=sum(1 for row in rows if row["outcome"] == "fp"),
                false_negative=sum(1 for row in rows if row["outcome"] == "fn"),
                output_csv=rel(output_csv),
            )
        )

    args.output_dir.mkdir(parents=True, exist_ok=True)
    summary_json = args.output_dir / "summary.json"
    summary_md = args.output_dir / "SUMMARY.md"
    summary_json.write_text(
        json.dumps([asdict(item) for item in summaries], ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_markdown(summary_md, summaries)
    print(json.dumps({"output_dir": rel(args.output_dir), "summaries": [asdict(item) for item in summaries]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
