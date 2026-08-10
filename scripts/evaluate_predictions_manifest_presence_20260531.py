#!/usr/bin/env python3
"""Image-level presence sweep from saved predictions.json plus a manifest CSV.

Use this when the original image/label dataset directory has been cleaned up
but a manifest still records label_box_count and an Ultralytics predictions.json
artifact remains.  It is a coarse image-level presence check, not bbox mAP.
"""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PREDICTIONS = (
    REPO_ROOT
    / "runs/detect/runs/detect/walksafe_kr_tactile_v2_aihub513_vl2_vs2_tactile_subset/predictions.json"
)
DEFAULT_MANIFEST = REPO_ROOT / "data_sources/manifests/walksafe_kr_v3_holdout_materialized_manifest_2026-05-20.csv"
DEFAULT_OUTPUT_DIR = REPO_ROOT / "reports/runs/evaluations/predictions_manifest_presence_20260531"


@dataclass(frozen=True)
class PresenceMetrics:
    threshold: float
    total_images: int
    true_positive: int
    false_positive: int
    false_negative: int
    true_negative: int
    precision: float | None
    recall: float | None
    f1: float | None
    accuracy: float | None


@dataclass(frozen=True)
class EvaluationSummary:
    predictions_json: str
    manifest_csv: str
    output_dir: str
    target_category_id: int
    source_dataset: str | None
    manifest_rows: int
    matched_rows: int
    unmatched_rows: int
    prediction_image_count: int
    metrics: list[PresenceMetrics]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate image-level presence from predictions.json + manifest.")
    parser.add_argument("--predictions-json", type=Path, default=DEFAULT_PREDICTIONS)
    parser.add_argument("--manifest-csv", type=Path, default=DEFAULT_MANIFEST)
    parser.add_argument("--target-category-id", type=int, default=1)
    parser.add_argument(
        "--threshold",
        type=float,
        action="append",
        help="Score threshold. Can be repeated. Defaults to 0.30..0.90 and 0.95.",
    )
    parser.add_argument("--source-dataset", help="Optional manifest source_dataset filter.")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    return parser.parse_args()


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def default_thresholds() -> list[float]:
    return [0.30, 0.40, 0.50, 0.60, 0.70, 0.75, 0.80, 0.85, 0.90, 0.95]


def safe_div(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return numerator / denominator


def rounded(value: float | None) -> float | None:
    return None if value is None else round(value, 6)


def image_stem_from_row(row: dict[str, str]) -> str:
    for column in ("image_path", "file_name", "materialized_image_path"):
        value = row.get(column)
        if value:
            return Path(value).stem
    raise ValueError("manifest row does not contain an image path column")


def load_manifest(path: Path, source_dataset: str | None) -> tuple[list[dict[str, object]], int]:
    rows: list[dict[str, object]] = []
    total = 0
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames or "label_box_count" not in reader.fieldnames:
            raise SystemExit("manifest must contain label_box_count")
        for raw in reader:
            total += 1
            if source_dataset and raw.get("source_dataset") != source_dataset:
                continue
            try:
                box_count = int(float(raw.get("label_box_count", "0") or 0))
            except ValueError:
                box_count = 0
            rows.append(
                {
                    "image_id": image_stem_from_row(raw),
                    "source_dataset": raw.get("source_dataset"),
                    "gt_positive": box_count > 0,
                    "label_box_count": box_count,
                }
            )
    return rows, total


def load_prediction_scores(path: Path, target_category_id: int) -> dict[str, list[float]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise SystemExit("predictions JSON must be a list")
    scores_by_image: dict[str, list[float]] = defaultdict(list)
    for item in payload:
        if not isinstance(item, dict):
            continue
        if int(item.get("category_id", -1)) != target_category_id:
            continue
        image_id = str(item.get("image_id") or Path(str(item.get("file_name", ""))).stem)
        if not image_id:
            continue
        try:
            score = float(item["score"])
        except (KeyError, TypeError, ValueError):
            continue
        scores_by_image[image_id].append(score)
    for scores in scores_by_image.values():
        scores.sort(reverse=True)
    return dict(scores_by_image)


def evaluate_threshold(
    rows: list[dict[str, object]],
    scores_by_image: dict[str, list[float]],
    threshold: float,
) -> tuple[PresenceMetrics, list[dict[str, object]]]:
    tp = fp = fn = tn = 0
    per_image: list[dict[str, object]] = []
    for row in rows:
        image_id = str(row["image_id"])
        gt_positive = bool(row["gt_positive"])
        scores = scores_by_image.get(image_id, [])
        max_score = scores[0] if scores else None
        pred_positive = max_score is not None and max_score >= threshold
        if gt_positive and pred_positive:
            outcome = "tp"
            tp += 1
        elif not gt_positive and pred_positive:
            outcome = "fp"
            fp += 1
        elif gt_positive and not pred_positive:
            outcome = "fn"
            fn += 1
        else:
            outcome = "tn"
            tn += 1
        per_image.append(
            {
                "threshold": f"{threshold:.2f}",
                "image_id": image_id,
                "source_dataset": row["source_dataset"],
                "gt_positive": gt_positive,
                "pred_positive": pred_positive,
                "outcome": outcome,
                "label_box_count": row["label_box_count"],
                "target_prediction_count": len(scores),
                "target_max_confidence": max_score,
                "target_top3_confidences": ";".join(f"{score:.6f}" for score in scores[:3]),
            }
        )

    precision = safe_div(tp, tp + fp)
    recall = safe_div(tp, tp + fn)
    f1 = None if precision is None or recall is None or precision + recall == 0 else 2 * precision * recall / (precision + recall)
    accuracy = safe_div(tp + tn, len(rows))
    return (
        PresenceMetrics(
            threshold=threshold,
            total_images=len(rows),
            true_positive=tp,
            false_positive=fp,
            false_negative=fn,
            true_negative=tn,
            precision=rounded(precision),
            recall=rounded(recall),
            f1=rounded(f1),
            accuracy=rounded(accuracy),
        ),
        per_image,
    )


def write_csv(path: Path, rows: Iterable[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "threshold",
        "image_id",
        "source_dataset",
        "gt_positive",
        "pred_positive",
        "outcome",
        "label_box_count",
        "target_prediction_count",
        "target_max_confidence",
        "target_top3_confidences",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_markdown(path: Path, summary: EvaluationSummary) -> None:
    lines = [
        "# predictions.json + manifest image-level presence sweep",
        "",
        f"- predictions: `{summary.predictions_json}`",
        f"- manifest: `{summary.manifest_csv}`",
        f"- target_category_id: `{summary.target_category_id}`",
        f"- source_dataset filter: `{summary.source_dataset or 'auto/all matched'}`",
        f"- matched rows: `{summary.matched_rows}` / manifest rows considered `{summary.manifest_rows}`",
        "",
        "| threshold | total | TP | FP | FN | TN | precision | recall | f1 | accuracy |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for metric in summary.metrics:
        lines.append(
            f"| {metric.threshold:.2f} | {metric.total_images} | {metric.true_positive} | "
            f"{metric.false_positive} | {metric.false_negative} | {metric.true_negative} | "
            f"{metric.precision} | {metric.recall} | {metric.f1} | {metric.accuracy} |"
        )
    lines += [
        "",
        "Limitations:",
        "",
        "- Uses `label_box_count > 0` as image-level GT positive.",
        "- Does not compute bbox IoU/mAP.",
        "- Depends on the saved predictions artifact and manifest; it is not a fresh inference run.",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    if not args.predictions_json.exists():
        raise SystemExit(f"predictions json not found: {args.predictions_json}")
    if not args.manifest_csv.exists():
        raise SystemExit(f"manifest csv not found: {args.manifest_csv}")
    thresholds = sorted(set(args.threshold or default_thresholds()))
    for threshold in thresholds:
        if threshold < 0 or threshold > 1:
            raise SystemExit("--threshold must be between 0 and 1")

    scores_by_image = load_prediction_scores(args.predictions_json, args.target_category_id)
    manifest_rows, manifest_total = load_manifest(args.manifest_csv, args.source_dataset)
    if args.source_dataset is None:
        # Keep rows that have a corresponding prediction artifact.  This avoids
        # counting VL1 hard-negative rows when evaluating the VL2 predictions file.
        manifest_rows = [row for row in manifest_rows if str(row["image_id"]) in scores_by_image]
    matched_rows = sum(1 for row in manifest_rows if str(row["image_id"]) in scores_by_image)
    unmatched_rows = len(manifest_rows) - matched_rows
    if not manifest_rows:
        raise SystemExit("no manifest rows selected for evaluation")

    all_per_image: list[dict[str, object]] = []
    metrics: list[PresenceMetrics] = []
    for threshold in thresholds:
        metric, per_image = evaluate_threshold(manifest_rows, scores_by_image, threshold)
        metrics.append(metric)
        all_per_image.extend(per_image)

    args.output_dir.mkdir(parents=True, exist_ok=True)
    summary = EvaluationSummary(
        predictions_json=rel(args.predictions_json),
        manifest_csv=rel(args.manifest_csv),
        output_dir=rel(args.output_dir),
        target_category_id=args.target_category_id,
        source_dataset=args.source_dataset,
        manifest_rows=len(manifest_rows) if args.source_dataset is None else manifest_total,
        matched_rows=matched_rows,
        unmatched_rows=unmatched_rows,
        prediction_image_count=len(scores_by_image),
        metrics=metrics,
    )
    (args.output_dir / "summary.json").write_text(
        json.dumps(asdict(summary), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_csv(args.output_dir / "per_image.csv", all_per_image)
    write_markdown(args.output_dir / "SUMMARY.md", summary)
    print(json.dumps(asdict(summary), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
