#!/usr/bin/env python3
"""Evaluate image-level presence for a YOLO class from saved label/prediction txt files.

This script does not run model inference. It compares existing YOLO-format ground
truth labels with existing YOLO-format prediction labels and answers the service
question: "does this image contain at least one target object?".

Prediction rows may be either:
- cls x_center y_center width height
- cls x_center y_center width height confidence
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


@dataclass(frozen=True)
class ImageLevelMetrics:
    target_class_name: str | None
    target_class_ids: list[int]
    confidence_threshold: float
    total_images: int
    true_positive: int
    false_positive: int
    false_negative: int
    true_negative: int
    precision: float | None
    recall: float | None
    f1: float | None
    accuracy: float | None


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Image-level presence metrics for one or more YOLO class ids from saved prediction txt files."
    )
    parser.add_argument("--gt-label-dir", type=Path, required=True, help="YOLO ground-truth label directory")
    parser.add_argument("--pred-label-dir", type=Path, required=True, help="YOLO prediction label directory")
    parser.add_argument("--image-dir", type=Path, help="Optional image directory defining the full evaluation universe")
    parser.add_argument("--data-yaml", type=Path, help="Optional data.yaml for resolving --target-class-name")
    parser.add_argument("--target-class-name", default="damaged_tactile_block")
    parser.add_argument(
        "--target-class-id",
        type=int,
        action="append",
        help="Target class id. Can be repeated. Overrides --target-class-name resolution when provided.",
    )
    parser.add_argument("--confidence-threshold", type=float, default=0.0)
    parser.add_argument("--out-json", type=Path)
    parser.add_argument("--out-csv", type=Path, help="Optional per-image CSV output")
    return parser


def parse_simple_yolo_names(data_yaml: Path) -> dict[str, int]:
    text = data_yaml.read_text(encoding="utf-8")
    names: dict[str, int] = {}
    in_names = False
    for raw_line in text.splitlines():
        line = raw_line.rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped == "names:" or stripped.startswith("names:"):
            in_names = True
            continue
        if in_names and not raw_line.startswith((" ", "\t")):
            in_names = False
        if not in_names:
            continue
        match = re.match(r"\s*(\d+)\s*:\s*['\"]?([^'\"]+)['\"]?\s*$", line)
        if match:
            names[match.group(2).strip()] = int(match.group(1))
    return names


def resolve_target_ids(args: argparse.Namespace) -> list[int]:
    if args.target_class_id:
        return sorted(set(args.target_class_id))
    if not args.data_yaml:
        raise SystemExit("--data-yaml or --target-class-id is required")
    names = parse_simple_yolo_names(args.data_yaml)
    if args.target_class_name not in names:
        raise SystemExit(f"target class not found in data yaml: {args.target_class_name}")
    return [names[args.target_class_name]]


def image_stems(image_dir: Path | None) -> set[str]:
    if image_dir is None:
        return set()
    return {path.stem for path in image_dir.iterdir() if path.is_file() and path.suffix.lower() in IMAGE_SUFFIXES}


def label_stems(label_dir: Path) -> set[str]:
    if not label_dir.exists():
        return set()
    return {path.stem for path in label_dir.glob("*.txt") if path.is_file()}


def parse_label_presence(path: Path, target_class_ids: set[int], confidence_threshold: float, *, prediction: bool) -> bool:
    if not path.exists():
        return False
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        parts = stripped.split()
        if len(parts) < 5:
            continue
        try:
            class_id = int(float(parts[0]))
        except ValueError:
            continue
        if class_id not in target_class_ids:
            continue
        if prediction and len(parts) >= 6:
            try:
                confidence = float(parts[5])
            except ValueError:
                continue
            if confidence < confidence_threshold:
                continue
        return True
    return False


def safe_div(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return numerator / denominator


def rounded(value: float | None) -> float | None:
    return None if value is None else round(value, 6)


def evaluate(args: argparse.Namespace) -> tuple[ImageLevelMetrics, list[dict[str, object]]]:
    target_ids = resolve_target_ids(args)
    target_id_set = set(target_ids)
    stems = image_stems(args.image_dir)
    stems |= label_stems(args.gt_label_dir)
    stems |= label_stems(args.pred_label_dir)
    ordered_stems = sorted(stems)

    rows: list[dict[str, object]] = []
    tp = fp = fn = tn = 0
    for stem in ordered_stems:
        gt_positive = parse_label_presence(args.gt_label_dir / f"{stem}.txt", target_id_set, 0.0, prediction=False)
        pred_positive = parse_label_presence(
            args.pred_label_dir / f"{stem}.txt",
            target_id_set,
            args.confidence_threshold,
            prediction=True,
        )
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
        rows.append(
            {
                "image_id": stem,
                "gt_positive": gt_positive,
                "pred_positive": pred_positive,
                "outcome": outcome,
            }
        )

    precision = safe_div(tp, tp + fp)
    recall = safe_div(tp, tp + fn)
    f1 = None if precision is None or recall is None or precision + recall == 0 else 2 * precision * recall / (precision + recall)
    accuracy = safe_div(tp + tn, len(ordered_stems))
    metrics = ImageLevelMetrics(
        target_class_name=args.target_class_name if not args.target_class_id else None,
        target_class_ids=target_ids,
        confidence_threshold=args.confidence_threshold,
        total_images=len(ordered_stems),
        true_positive=tp,
        false_positive=fp,
        false_negative=fn,
        true_negative=tn,
        precision=rounded(precision),
        recall=rounded(recall),
        f1=rounded(f1),
        accuracy=rounded(accuracy),
    )
    return metrics, rows


def write_csv(path: Path, rows: Iterable[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = ["image_id", "gt_positive", "pred_positive", "outcome"]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    args = build_parser().parse_args()
    if not 0 <= args.confidence_threshold <= 1:
        raise SystemExit("--confidence-threshold must be between 0 and 1")
    metrics, rows = evaluate(args)
    payload = asdict(metrics)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    if args.out_json:
        args.out_json.parent.mkdir(parents=True, exist_ok=True)
        args.out_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.out_csv:
        write_csv(args.out_csv, rows)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
