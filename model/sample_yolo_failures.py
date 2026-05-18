#!/usr/bin/env python3
"""Stream YOLO test images and write bounded failure candidates."""

from __future__ import annotations

import argparse
import csv
import json
import random
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Any

from validate_yolo_dataset import IMAGE_EXTENSIONS, parse_data_yaml


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Sample YOLO failure candidates without saving prediction images."
    )
    parser.add_argument("--model", required=True, help="Path to a YOLO .pt/.onnx model.")
    parser.add_argument("--data", default="datasets/walksafe_kr_v2/data.yaml")
    parser.add_argument("--split", default="test", choices=("train", "val", "test"))
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--max-images", type=int, default=80, help="0 means all images.")
    parser.add_argument("--seed", type=int, default=20260519)
    parser.add_argument("--conf", type=float, default=0.25)
    parser.add_argument("--nms-iou", type=float, default=0.7)
    parser.add_argument("--match-iou", type=float, default=0.5)
    parser.add_argument("--small-area", type=float, default=0.015)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--bucket-limit", type=int, default=50)
    parser.add_argument("--max-candidates", type=int, default=200)
    parser.add_argument("--checkpoint-every", type=int, default=10)
    parser.add_argument("--overwrite", action="store_true")
    return parser.parse_args()


def resolve_dataset_root(data_yaml: Path, config: dict[str, object]) -> Path:
    configured_root = Path(str(config.get("path", ".")))
    if configured_root.is_absolute():
        return configured_root.resolve()
    if (Path.cwd() / configured_root).exists():
        return (Path.cwd() / configured_root).resolve()
    return (data_yaml.parent / configured_root).resolve()


def image_paths(image_dir: Path) -> list[Path]:
    return [
        path
        for path in sorted(image_dir.iterdir())
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    ]


def label_path_for(label_dir: Path, image_path: Path) -> Path:
    return label_dir / f"{image_path.stem}.txt"


def has_positive_label(label_path: Path) -> bool:
    if not label_path.exists():
        return False
    return bool(label_path.read_text(encoding="utf-8").strip())


def select_images(paths: list[Path], label_dir: Path, max_images: int, seed: int) -> list[Path]:
    if max_images <= 0 or max_images >= len(paths):
        return paths

    positives = [path for path in paths if has_positive_label(label_path_for(label_dir, path))]
    negatives = [path for path in paths if not has_positive_label(label_path_for(label_dir, path))]

    rng = random.Random(seed)
    rng.shuffle(positives)
    rng.shuffle(negatives)

    positive_limit = max_images // 2
    negative_limit = max_images - positive_limit
    selected = positives[:positive_limit] + negatives[:negative_limit]

    if len(selected) < max_images:
        selected_names = {path.name for path in selected}
        remaining = [path for path in positives + negatives if path.name not in selected_names]
        selected.extend(remaining[: max_images - len(selected)])

    return sorted(selected)


def read_labels(label_path: Path) -> list[dict[str, Any]]:
    labels: list[dict[str, Any]] = []
    if not label_path.exists():
        return labels

    for raw_line in label_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        class_id, x, y, w, h = line.split()
        labels.append(
            {
                "class_id": int(class_id),
                "xywhn": (float(x), float(y), float(w), float(h)),
                "raw": line,
            }
        )
    return labels


def xywh_to_xyxy(box: tuple[float, float, float, float]) -> tuple[float, float, float, float]:
    x, y, w, h = box
    return (x - w / 2, y - h / 2, x + w / 2, y + h / 2)


def iou_xywhn(
    first: tuple[float, float, float, float], second: tuple[float, float, float, float]
) -> float:
    ax1, ay1, ax2, ay2 = xywh_to_xyxy(first)
    bx1, by1, bx2, by2 = xywh_to_xyxy(second)
    inter_w = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    inter_h = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter_area = inter_w * inter_h
    if inter_area <= 0:
        return 0.0
    first_area = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    second_area = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = first_area + second_area - inter_area
    return inter_area / union if union > 0 else 0.0


def format_box(box: tuple[float, float, float, float] | None, confidence: float | None = None) -> str:
    if box is None:
        return ""
    values = ",".join(f"{value:.6f}" for value in box)
    if confidence is None:
        return values
    return f"{values}:{confidence:.6f}"


def write_checkpoint(path: Path, payload: dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    data_yaml = Path(args.data)
    config = parse_data_yaml(data_yaml)
    dataset_root = resolve_dataset_root(data_yaml, config)
    image_dir = dataset_root / str(config.get(args.split, f"images/{args.split}"))
    label_dir = dataset_root / "labels" / args.split

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    output_csv = output_dir / "failure_candidates.csv"
    summary_json = output_dir / "summary.json"
    checkpoint_json = output_dir / "checkpoint.json"

    if output_csv.exists() and not args.overwrite:
        print(f"ERROR: output already exists: {output_csv}")
        return 1

    paths = image_paths(image_dir)
    selected_paths = select_images(paths, label_dir, args.max_images, args.seed)
    selected_positive = sum(1 for path in selected_paths if has_positive_label(label_path_for(label_dir, path)))
    selected_negative = len(selected_paths) - selected_positive

    try:
        from ultralytics import YOLO
    except ImportError:
        print("ERROR: ultralytics is not installed. Use .venv/bin/python or install requirements-model.txt.")
        return 1

    model = YOLO(args.model)
    fieldnames = [
        "rank",
        "bucket",
        "source_dataset",
        "split",
        "image_path",
        "label_path",
        "expected_class",
        "predicted_class",
        "confidence",
        "max_iou",
        "gt_box_xywhn",
        "pred_box_xywhn",
        "gt_area",
        "action",
        "privacy_review_required",
        "notes",
    ]

    processed = 0
    total_gt_boxes = 0
    total_predictions = 0
    matched_gt_boxes = 0
    missed_gt_boxes = 0
    negative_images_with_prediction = 0
    bucket_events: Counter[str] = Counter()
    written_by_bucket: Counter[str] = Counter()
    candidate_rows_written = 0

    with output_csv.open("w", newline="", encoding="utf-8") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=fieldnames)
        writer.writeheader()

        for image_path in selected_paths:
            label_path = label_path_for(label_dir, image_path)
            labels = read_labels(label_path)
            total_gt_boxes += len(labels)

            result = model.predict(
                source=str(image_path),
                conf=args.conf,
                iou=args.nms_iou,
                imgsz=args.imgsz,
                device=args.device,
                verbose=False,
            )[0]
            boxes = result.boxes
            predictions: list[dict[str, Any]] = []
            if boxes is not None and len(boxes) > 0:
                classes = boxes.cls.cpu().tolist()
                confidences = boxes.conf.cpu().tolist()
                xywhn_boxes = boxes.xywhn.cpu().tolist()
                for class_id, confidence, box in zip(classes, confidences, xywhn_boxes):
                    predictions.append(
                        {
                            "class_id": int(class_id),
                            "confidence": float(confidence),
                            "xywhn": tuple(float(value) for value in box),
                        }
                    )

            total_predictions += len(predictions)
            if not labels and predictions:
                negative_images_with_prediction += 1

            for label in labels:
                same_class_predictions = [
                    prediction
                    for prediction in predictions
                    if prediction["class_id"] == label["class_id"]
                ]
                best_prediction = None
                best_iou = 0.0
                for prediction in same_class_predictions:
                    current_iou = iou_xywhn(label["xywhn"], prediction["xywhn"])
                    if current_iou > best_iou:
                        best_iou = current_iou
                        best_prediction = prediction

                if best_iou >= args.match_iou:
                    matched_gt_boxes += 1
                    continue

                missed_gt_boxes += 1
                gt_area = label["xywhn"][2] * label["xywhn"][3]
                bucket = "small_or_far" if gt_area <= args.small_area else "missed_defect"
                bucket_events[bucket] += 1

                if (
                    written_by_bucket[bucket] >= args.bucket_limit
                    or candidate_rows_written >= args.max_candidates
                ):
                    continue

                candidate_rows_written += 1
                written_by_bucket[bucket] += 1
                writer.writerow(
                    {
                        "rank": candidate_rows_written,
                        "bucket": bucket,
                        "source_dataset": dataset_root.name,
                        "split": args.split,
                        "image_path": str(image_path),
                        "label_path": str(label_path),
                        "expected_class": label["class_id"],
                        "predicted_class": ""
                        if best_prediction is None
                        else best_prediction["class_id"],
                        "confidence": ""
                        if best_prediction is None
                        else f"{best_prediction['confidence']:.6f}",
                        "max_iou": f"{best_iou:.6f}",
                        "gt_box_xywhn": format_box(label["xywhn"]),
                        "pred_box_xywhn": ""
                        if best_prediction is None
                        else format_box(best_prediction["xywhn"], best_prediction["confidence"]),
                        "gt_area": f"{gt_area:.6f}",
                        "action": "review_small_object_policy"
                        if bucket == "small_or_far"
                        else "review_missed_or_low_iou_defect",
                        "privacy_review_required": "yes",
                        "notes": "streaming_sampler_candidate_not_manual_reviewed",
                    }
                )

            for prediction in predictions:
                best_iou = 0.0
                for label in labels:
                    if label["class_id"] != prediction["class_id"]:
                        continue
                    best_iou = max(best_iou, iou_xywhn(label["xywhn"], prediction["xywhn"]))
                if best_iou >= args.match_iou:
                    continue

                bucket = "false_positive_normal_tactile" if not labels else "false_positive_extra_box"
                bucket_events[bucket] += 1
                if (
                    written_by_bucket[bucket] >= args.bucket_limit
                    or candidate_rows_written >= args.max_candidates
                ):
                    continue

                candidate_rows_written += 1
                written_by_bucket[bucket] += 1
                writer.writerow(
                    {
                        "rank": candidate_rows_written,
                        "bucket": bucket,
                        "source_dataset": dataset_root.name,
                        "split": args.split,
                        "image_path": str(image_path),
                        "label_path": str(label_path),
                        "expected_class": "none",
                        "predicted_class": prediction["class_id"],
                        "confidence": f"{prediction['confidence']:.6f}",
                        "max_iou": f"{best_iou:.6f}",
                        "gt_box_xywhn": "",
                        "pred_box_xywhn": format_box(prediction["xywhn"], prediction["confidence"]),
                        "gt_area": "",
                        "action": "add_as_hard_negative_and_check_threshold",
                        "privacy_review_required": "yes",
                        "notes": "streaming_sampler_candidate_not_manual_reviewed",
                    }
                )

            processed += 1
            if args.checkpoint_every > 0 and processed % args.checkpoint_every == 0:
                write_checkpoint(
                    checkpoint_json,
                    {
                        "updated_at": datetime.now().isoformat(timespec="seconds"),
                        "processed_images": processed,
                        "last_processed_image": str(image_path),
                        "candidate_rows_written": candidate_rows_written,
                    },
                )

    write_checkpoint(
        checkpoint_json,
        {
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "processed_images": processed,
            "last_processed_image": str(selected_paths[-1]) if selected_paths else None,
            "candidate_rows_written": candidate_rows_written,
            "complete": True,
        },
    )

    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "model": str(Path(args.model)),
        "data": str(data_yaml),
        "dataset": str(dataset_root),
        "split": args.split,
        "seed": args.seed,
        "conf": args.conf,
        "nms_iou": args.nms_iou,
        "match_iou": args.match_iou,
        "imgsz": args.imgsz,
        "device": args.device,
        "full_split_images_total": len(paths),
        "sample_images_total": len(selected_paths),
        "sample_positive_images": selected_positive,
        "sample_negative_images": selected_negative,
        "sample_gt_boxes_total": total_gt_boxes,
        "predictions_total": total_predictions,
        "matched_gt_boxes": matched_gt_boxes,
        "missed_gt_boxes": missed_gt_boxes,
        "negative_images_with_prediction": negative_images_with_prediction,
        "candidate_events_by_bucket": dict(sorted(bucket_events.items())),
        "candidate_rows_written": candidate_rows_written,
        "candidate_rows_by_bucket": dict(sorted(written_by_bucket.items())),
        "output_csv": str(output_csv),
        "checkpoint": str(checkpoint_json),
        "note": "No prediction images or contact sheets were written by this run.",
    }
    summary_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
