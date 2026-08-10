#!/usr/bin/env python3
"""Dry-run inspect AI Hub 513 VL/VS validation zip pairs.

This script only reads zip central directories and label JSON members. It does
not extract source images or write dataset files.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
VALID_PAIRS = {"1": ("VL1.zip", "VS1.zip"), "2": ("VL2.zip", "VS2.zip")}
TARGET_LABEL_NAME = "점자블럭"
TARGET_FACILITY = "2_09"
DEFECT_PREFIX = "불량"


@dataclass
class TactileSample:
    filename: str
    width: int
    height: int
    source_label_zip: str
    source_members: list[str] = field(default_factory=list)
    boxes: int = 0
    defective_annotations: int = 0
    invalid_defective_boxes: int = 0

    @property
    def is_positive(self) -> bool:
        return self.boxes > 0


@dataclass
class LabelScan:
    label_json_files: int = 0
    label_files_with_filename: int = 0
    duplicate_label_filenames: int = 0
    invalid_label_json_files: int = 0
    label_parse_errors: list[dict[str, str]] = field(default_factory=list)
    all_label_filenames: Counter[str] = field(default_factory=Counter)
    tactile_label_files: int = 0
    tactile_positive_label_files: int = 0
    tactile_defective_annotations: int = 0
    tactile_invalid_defective_boxes: int = 0
    tactile_samples: dict[str, TactileSample] = field(default_factory=dict)


@dataclass
class ImageScan:
    image_files: int = 0
    duplicate_image_filenames: int = 0
    image_filenames: Counter[str] = field(default_factory=Counter)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Dry-run inspect one AI Hub 513 validation pair (VL1+VS1 or VL2+VS2) "
            "without extracting images."
        )
    )
    parser.add_argument("--pair", choices=sorted(VALID_PAIRS), default="1", help="Validation pair number to inspect.")
    parser.add_argument(
        "--download-root",
        default=str(Path.home() / "Downloads"),
        help="Directory searched recursively for VL*/VS*.zip when explicit paths are not provided.",
    )
    parser.add_argument("--label-zip", type=Path, help="Explicit VL zip path. Must match --pair.")
    parser.add_argument("--image-zip", type=Path, help="Explicit VS zip path. Must match --pair.")
    parser.add_argument(
        "--json-summary",
        type=Path,
        help="Optional path for a machine-readable summary JSON. Prefer an ignored path such as /tmp.",
    )
    parser.add_argument("--sample-limit", type=int, default=10, help="Maximum sample filenames to print/store per list.")
    parser.add_argument("--quiet", action="store_true", help="Only print the final summary.")
    return parser.parse_args()


def find_zip(download_root: Path, name: str) -> Path:
    matches = sorted(path for path in download_root.expanduser().rglob(name) if path.is_file())
    if not matches:
        raise FileNotFoundError(f"Could not find {name} under {download_root}")
    return matches[0]


def validate_zip_path(path: Path, expected_name: str, option_name: str) -> Path:
    resolved = path.expanduser()
    if resolved.name != expected_name:
        raise ValueError(f"{option_name} must point to {expected_name} for the selected pair, got {resolved.name}")
    if not resolved.is_file():
        raise FileNotFoundError(f"{option_name} does not exist: {resolved}")
    return resolved


def resolve_pair(args: argparse.Namespace) -> tuple[Path, Path, str]:
    expected_label, expected_image = VALID_PAIRS[args.pair]
    if bool(args.label_zip) != bool(args.image_zip):
        raise ValueError("--label-zip and --image-zip must be provided together")

    if args.label_zip and args.image_zip:
        label_zip = validate_zip_path(args.label_zip, expected_label, "--label-zip")
        image_zip = validate_zip_path(args.image_zip, expected_image, "--image-zip")
    else:
        download_root = Path(args.download_root).expanduser()
        label_zip = find_zip(download_root, expected_label)
        image_zip = find_zip(download_root, expected_image)

    return label_zip, image_zip, f"{expected_label.removesuffix('.zip')}+{expected_image.removesuffix('.zip')}"


def bbox_is_valid(annotation: dict[str, Any], width: int, height: int) -> bool:
    points = annotation.get("annotation_info")
    if not points:
        return False

    annotation_type = annotation.get("annotation_type")
    try:
        if annotation_type == "bbox":
            bbox = points[0] if isinstance(points, list) and points else points
            if not isinstance(bbox, list) or len(bbox) < 4:
                return False
            x_min, y_min, box_width, box_height = (float(value) for value in bbox[:4])
            x_max = x_min + box_width
            y_max = y_min + box_height
        else:
            if not isinstance(points, list):
                return False
            xs: list[float] = []
            ys: list[float] = []
            for point in points:
                if not isinstance(point, list) or len(point) < 2:
                    continue
                xs.append(float(point[0]))
                ys.append(float(point[1]))
            if not xs or not ys:
                return False
            x_min = min(xs)
            x_max = max(xs)
            y_min = min(ys)
            y_max = max(ys)
    except (TypeError, ValueError):
        return False

    x_min = max(0.0, min(float(width), x_min))
    x_max = max(0.0, min(float(width), x_max))
    y_min = max(0.0, min(float(height), y_min))
    y_max = max(0.0, min(float(height), y_max))
    return width > 0 and height > 0 and (x_max - x_min) > 1 and (y_max - y_min) > 1


def read_json_member(archive: zipfile.ZipFile, member: str) -> Any:
    with archive.open(member) as file:
        return json.load(file)


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def scan_labels(label_zip: Path, quiet: bool = False) -> LabelScan:
    scan = LabelScan()
    with zipfile.ZipFile(label_zip) as archive:
        json_members = [info.filename for info in archive.infolist() if not info.is_dir() and info.filename.lower().endswith(".json")]
        scan.label_json_files = len(json_members)

        for index, member in enumerate(json_members, start=1):
            if not quiet and index % 5000 == 0:
                print(f"Read {index:,}/{len(json_members):,} label JSON files...", file=sys.stderr)

            try:
                data = read_json_member(archive, member)
            except (json.JSONDecodeError, UnicodeDecodeError, zipfile.BadZipFile, OSError) as error:
                scan.invalid_label_json_files += 1
                if len(scan.label_parse_errors) < 20:
                    scan.label_parse_errors.append({"member": member, "error": str(error)})
                continue

            if not isinstance(data, dict):
                scan.invalid_label_json_files += 1
                if len(scan.label_parse_errors) < 20:
                    scan.label_parse_errors.append({"member": member, "error": "top-level JSON is not an object"})
                continue

            info = data.get("info", {})
            description = data.get("description", {})
            if not isinstance(info, dict):
                info = {}
            if not isinstance(description, dict):
                description = {}

            filename = str(info.get("filename", "")).strip()
            width = safe_int(info.get("width", 0))
            height = safe_int(info.get("height", 0))
            if filename:
                scan.label_files_with_filename += 1
                scan.all_label_filenames[filename] += 1

            if not filename or width <= 0 or height <= 0:
                continue

            annotations = data.get("annotations", [])
            if not isinstance(annotations, list):
                annotations = []

            is_tactile_file = TARGET_LABEL_NAME in member or description.get("facility") == TARGET_FACILITY
            has_tactile_annotation = False
            file_boxes = 0
            file_defective_annotations = 0
            file_invalid_defective_boxes = 0

            for annotation in annotations:
                if not isinstance(annotation, dict) or annotation.get("label_name") != TARGET_LABEL_NAME:
                    continue
                has_tactile_annotation = True
                if str(annotation.get("is_defect", "")).startswith(DEFECT_PREFIX):
                    file_defective_annotations += 1
                    if bbox_is_valid(annotation, width, height):
                        file_boxes += 1
                    else:
                        file_invalid_defective_boxes += 1

            if not is_tactile_file and not has_tactile_annotation:
                continue

            scan.tactile_label_files += 1
            scan.tactile_defective_annotations += file_defective_annotations
            scan.tactile_invalid_defective_boxes += file_invalid_defective_boxes
            if file_boxes > 0:
                scan.tactile_positive_label_files += 1

            sample = scan.tactile_samples.setdefault(
                filename,
                TactileSample(
                    filename=filename,
                    width=width,
                    height=height,
                    source_label_zip=label_zip.name,
                ),
            )
            sample.source_members.append(member)
            sample.boxes += file_boxes
            sample.defective_annotations += file_defective_annotations
            sample.invalid_defective_boxes += file_invalid_defective_boxes

    scan.duplicate_label_filenames = sum(count - 1 for count in scan.all_label_filenames.values() if count > 1)
    return scan


def scan_images(image_zip: Path) -> ImageScan:
    scan = ImageScan()
    with zipfile.ZipFile(image_zip) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            suffix = Path(info.filename).suffix.lower()
            if suffix not in IMAGE_EXTENSIONS:
                continue
            filename = Path(info.filename).name
            scan.image_files += 1
            scan.image_filenames[filename] += 1

    scan.duplicate_image_filenames = sum(count - 1 for count in scan.image_filenames.values() if count > 1)
    return scan


def sorted_limited(values: set[str], limit: int) -> list[str]:
    if limit <= 0:
        return []
    return sorted(values)[:limit]


def build_summary(
    pair_name: str,
    label_zip: Path,
    image_zip: Path,
    label_scan: LabelScan,
    image_scan: ImageScan,
    elapsed_seconds: float,
    sample_limit: int,
) -> dict[str, Any]:
    all_label_names = set(label_scan.all_label_filenames)
    image_names = set(image_scan.image_filenames)
    tactile_names = set(label_scan.tactile_samples)
    positive_tactile_names = {filename for filename, sample in label_scan.tactile_samples.items() if sample.is_positive}
    negative_tactile_names = tactile_names - positive_tactile_names

    missing_all = all_label_names - image_names
    unmatched_all = image_names - all_label_names
    missing_tactile = tactile_names - image_names
    unmatched_tactile = image_names - tactile_names

    tactile_boxes = sum(sample.boxes for sample in label_scan.tactile_samples.values())
    tactile_defective_annotations = sum(sample.defective_annotations for sample in label_scan.tactile_samples.values())
    tactile_invalid_defective_boxes = sum(sample.invalid_defective_boxes for sample in label_scan.tactile_samples.values())

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "pair": pair_name,
        "label_zip": str(label_zip),
        "image_zip": str(image_zip),
        "elapsed_seconds": round(elapsed_seconds, 3),
        "read_policy": "zip central directory plus label JSON members only; no image extraction",
        "label_parsing_rule": {
            "tactile_file": "member path contains 점자블럭 or description.facility == 2_09",
            "tactile_annotation": "annotation.label_name == 점자블럭",
            "positive_box": "annotation.is_defect starts with 불량 and bbox/polygon is valid after clipping",
        },
        "full_pair": {
            "label_json_files": label_scan.label_json_files,
            "label_files_with_filename": label_scan.label_files_with_filename,
            "unique_label_filenames": len(all_label_names),
            "duplicate_label_filenames": label_scan.duplicate_label_filenames,
            "invalid_label_json_files": label_scan.invalid_label_json_files,
            "image_files": image_scan.image_files,
            "unique_image_filenames": len(image_names),
            "duplicate_image_filenames": image_scan.duplicate_image_filenames,
            "matched_label_filenames": len(all_label_names & image_names),
            "missing_image_count": len(missing_all),
            "unmatched_image_count": len(unmatched_all),
        },
        "tactile": {
            "tactile_label_files": label_scan.tactile_label_files,
            "tactile_unique_filenames": len(tactile_names),
            "defective_positive_count": len(positive_tactile_names),
            "negative_count": len(negative_tactile_names),
            "boxes_count": tactile_boxes,
            "defective_annotations": tactile_defective_annotations,
            "invalid_defective_boxes": tactile_invalid_defective_boxes,
            "positive_label_files": label_scan.tactile_positive_label_files,
            "matched_image_count": len(tactile_names & image_names),
            "missing_image_count": len(missing_tactile),
            "unmatched_image_count": len(unmatched_tactile),
        },
        "samples": {
            "missing_images": sorted_limited(missing_all, sample_limit),
            "unmatched_images": sorted_limited(unmatched_all, sample_limit),
            "tactile_missing_images": sorted_limited(missing_tactile, sample_limit),
            "tactile_unmatched_images": sorted_limited(unmatched_tactile, sample_limit),
            "label_parse_errors": label_scan.label_parse_errors[:sample_limit],
        },
    }


def print_summary(summary: dict[str, Any]) -> None:
    full = summary["full_pair"]
    tactile = summary["tactile"]
    samples = summary["samples"]

    print("AI Hub 513 VL/VS dry-run inspection")
    print(f"Pair: {summary['pair']}")
    print(f"Label zip: {summary['label_zip']}")
    print(f"Image zip: {summary['image_zip']}")
    print(f"Elapsed: {summary['elapsed_seconds']:.3f}s")
    print()
    print("Full pair matching")
    print(f"  Label JSON files: {full['label_json_files']:,}")
    print(f"  Labels with filename: {full['label_files_with_filename']:,}")
    print(f"  Unique label filenames: {full['unique_label_filenames']:,}")
    print(f"  Image files: {full['image_files']:,}")
    print(f"  Unique image filenames: {full['unique_image_filenames']:,}")
    print(f"  Matched label filenames: {full['matched_label_filenames']:,}")
    print(f"  Missing image count: {full['missing_image_count']:,}")
    print(f"  Unmatched image count: {full['unmatched_image_count']:,}")
    if full["duplicate_label_filenames"] or full["duplicate_image_filenames"] or full["invalid_label_json_files"]:
        print(
            "  Notes: "
            f"{full['duplicate_label_filenames']:,} duplicate label filenames, "
            f"{full['duplicate_image_filenames']:,} duplicate image filenames, "
            f"{full['invalid_label_json_files']:,} invalid label JSON files"
        )
    print()
    print("Tactile parsing (build_walksafe_kr_tactile rules)")
    print(f"  Tactile label files: {tactile['tactile_label_files']:,}")
    print(f"  Tactile unique filenames: {tactile['tactile_unique_filenames']:,}")
    print(f"  Defective/positive count: {tactile['defective_positive_count']:,}")
    print(f"  Negative count: {tactile['negative_count']:,}")
    print(f"  Boxes count: {tactile['boxes_count']:,}")
    print(f"  Defective annotations: {tactile['defective_annotations']:,}")
    print(f"  Invalid defective boxes: {tactile['invalid_defective_boxes']:,}")
    print(f"  Matched tactile images: {tactile['matched_image_count']:,}")
    print(f"  Missing tactile image count: {tactile['missing_image_count']:,}")
    print(f"  Unmatched image count vs tactile subset: {tactile['unmatched_image_count']:,}")

    if samples["missing_images"] or samples["tactile_missing_images"] or samples["label_parse_errors"]:
        print()
        print("Samples")
        if samples["missing_images"]:
            print(f"  Missing images: {', '.join(samples['missing_images'])}")
        if samples["tactile_missing_images"]:
            print(f"  Missing tactile images: {', '.join(samples['tactile_missing_images'])}")
        if samples["label_parse_errors"]:
            print(f"  Label parse errors: {samples['label_parse_errors']}")


def write_json_summary(path: Path, summary: dict[str, Any]) -> None:
    path = path.expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    started = time.monotonic()

    try:
        label_zip, image_zip, pair_name = resolve_pair(args)
        label_scan = scan_labels(label_zip, quiet=args.quiet)
        image_scan = scan_images(image_zip)
        summary = build_summary(
            pair_name=pair_name,
            label_zip=label_zip,
            image_zip=image_zip,
            label_scan=label_scan,
            image_scan=image_scan,
            elapsed_seconds=time.monotonic() - started,
            sample_limit=args.sample_limit,
        )
        print_summary(summary)
        if args.json_summary:
            write_json_summary(args.json_summary, summary)
            print()
            print(f"Wrote JSON summary: {args.json_summary.expanduser()}")
    except (FileNotFoundError, ValueError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
