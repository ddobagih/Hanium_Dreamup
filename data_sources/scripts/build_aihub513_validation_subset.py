#!/usr/bin/env python3
"""Build a YOLO external validation subset from AI Hub 513 VL/VS zip pairs."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import shutil
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

TARGET_CLASS_ID = 0
TARGET_CLASS_NAME = "damaged_tactile_block"
TARGET_LABEL_NAME = "점자블럭"
TARGET_FACILITY = "2_09"
DEFECT_PREFIX = "불량"

CLASS_NAMES = {
    0: "damaged_tactile_block",
    1: "parked_kickboard_bicycle",
    2: "construction_obstacle",
    3: "pothole",
}


@dataclass
class Sample:
    filename: str
    width: int
    height: int
    source_label_zip: str
    date: str = ""
    city_id: str = ""
    source_members: list[str] = field(default_factory=list)
    boxes: list[tuple[float, float, float, float]] = field(default_factory=list)
    defective_annotations: int = 0
    invalid_defective_boxes: int = 0

    @property
    def is_positive(self) -> bool:
        return bool(self.boxes)


@dataclass
class LabelScan:
    label_json_files: int = 0
    label_files_with_filename: int = 0
    duplicate_label_filenames: int = 0
    invalid_label_json_files: int = 0
    tactile_label_files: int = 0
    tactile_positive_label_files: int = 0
    tactile_defective_annotations: int = 0
    tactile_invalid_defective_boxes: int = 0
    dimension_mismatches: int = 0
    all_label_filenames: Counter[str] = field(default_factory=Counter)
    label_parse_errors: list[dict[str, str]] = field(default_factory=list)
    tactile_samples: dict[str, Sample] = field(default_factory=dict)


@dataclass
class ImageMember:
    member: str
    file_size: int


@dataclass
class ImageIndex:
    image_files: int = 0
    duplicate_image_filenames: int = 0
    image_filenames: Counter[str] = field(default_factory=Counter)
    members: dict[str, ImageMember] = field(default_factory=dict)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Build a YOLO val subset from one AI Hub 513 validation pair "
            "(VL1+VS1 or VL2+VS2). The script accepts VL/VS paths directly; "
            "no TL/TS renaming is required."
        )
    )
    parser.add_argument("--pair", choices=sorted(VALID_PAIRS), default="2", help="Validation pair number.")
    parser.add_argument(
        "--download-root",
        default=str(Path.home() / "Downloads"),
        help="Directory searched recursively for VL*/VS*.zip when explicit paths are not provided.",
    )
    parser.add_argument("--label-zip", type=Path, help="Explicit VL zip path. Must match --pair.")
    parser.add_argument("--image-zip", type=Path, help="Explicit VS zip path. Must match --pair.")
    parser.add_argument(
        "--output-dir",
        "--target",
        dest="output_dir",
        type=Path,
        help="Output dataset directory. Defaults to an ignored runs/validation path for the selected pair.",
    )
    parser.add_argument("--split", default="val", choices=("val", "test"), help="YOLO split directory to populate.")
    parser.add_argument("--positive-limit", type=int, default=0, help="Maximum positive images. 0 means all positives.")
    parser.add_argument(
        "--negative-limit",
        type=int,
        default=0,
        help="Maximum negative images when --include-negatives is set. 0 means all negatives.",
    )
    parser.add_argument(
        "--include-negatives",
        action="store_true",
        help="Include normal tactile-block images as hard negatives with empty label files.",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--dry-run", action="store_true", help="Scan and select samples without writing files.")
    parser.add_argument("--overwrite", action="store_true", help="Remove an existing output directory before building.")
    parser.add_argument(
        "--json-summary",
        type=Path,
        help="Optional path for a machine-readable summary JSON. Prefer an ignored path under runs/.",
    )
    parser.add_argument("--sample-limit", type=int, default=10, help="Maximum sample filenames to print/store per list.")
    parser.add_argument("--quiet", action="store_true", help="Suppress progress messages while reading label JSON.")
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


def resolve_pair(args: argparse.Namespace) -> tuple[Path, Path, str, str]:
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

    pair_name = f"{expected_label.removesuffix('.zip')}+{expected_image.removesuffix('.zip')}"
    pair_key = pair_name.lower().replace("+", "_")
    return label_zip, image_zip, pair_name, pair_key


def default_output_dir(pair_key: str) -> Path:
    return Path("runs") / "validation" / f"aihub513_{pair_key}_tactile_subset"


def safe_int(value: Any) -> int:
    try:
        return int(value or 0)
    except (TypeError, ValueError):
        return 0


def bbox_from_annotation(annotation: dict[str, Any], width: int, height: int) -> tuple[float, float, float, float] | None:
    points = annotation.get("annotation_info")
    if not points:
        return None

    try:
        annotation_type = annotation.get("annotation_type")
        if annotation_type == "bbox":
            bbox = points[0] if isinstance(points, list) and points else points
            if not isinstance(bbox, list) or len(bbox) < 4:
                return None
            x_min, y_min, box_width, box_height = (float(value) for value in bbox[:4])
            x_max = x_min + box_width
            y_max = y_min + box_height
        else:
            if not isinstance(points, list):
                return None
            xs: list[float] = []
            ys: list[float] = []
            for point in points:
                if not isinstance(point, list) or len(point) < 2:
                    continue
                xs.append(float(point[0]))
                ys.append(float(point[1]))
            if not xs or not ys:
                return None
            x_min = min(xs)
            x_max = max(xs)
            y_min = min(ys)
            y_max = max(ys)
    except (TypeError, ValueError):
        return None

    x_min = max(0.0, min(float(width), x_min))
    x_max = max(0.0, min(float(width), x_max))
    y_min = max(0.0, min(float(height), y_min))
    y_max = max(0.0, min(float(height), y_max))
    box_width = x_max - x_min
    box_height = y_max - y_min
    if width <= 0 or height <= 0 or box_width <= 1 or box_height <= 1:
        return None

    x_center = (x_min + x_max) / 2 / width
    y_center = (y_min + y_max) / 2 / height
    return (x_center, y_center, box_width / width, box_height / height)


def read_json_member(archive: zipfile.ZipFile, member: str) -> Any:
    with archive.open(member) as file:
        return json.load(file)


def read_tactile_samples(label_zip: Path, quiet: bool = False) -> LabelScan:
    scan = LabelScan()
    with zipfile.ZipFile(label_zip) as archive:
        json_members = [
            info.filename
            for info in archive.infolist()
            if not info.is_dir() and info.filename.lower().endswith(".json")
        ]
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
            boxes: list[tuple[float, float, float, float]] = []
            defective_annotations = 0
            invalid_defective_boxes = 0

            for annotation in annotations:
                if not isinstance(annotation, dict) or annotation.get("label_name") != TARGET_LABEL_NAME:
                    continue
                has_tactile_annotation = True
                if str(annotation.get("is_defect", "")).startswith(DEFECT_PREFIX):
                    defective_annotations += 1
                    bbox = bbox_from_annotation(annotation, width, height)
                    if bbox:
                        boxes.append(bbox)
                    else:
                        invalid_defective_boxes += 1

            if not is_tactile_file and not has_tactile_annotation:
                continue

            scan.tactile_label_files += 1
            scan.tactile_defective_annotations += defective_annotations
            scan.tactile_invalid_defective_boxes += invalid_defective_boxes
            if boxes:
                scan.tactile_positive_label_files += 1

            sample = scan.tactile_samples.setdefault(
                filename,
                Sample(
                    filename=filename,
                    width=width,
                    height=height,
                    source_label_zip=label_zip.name,
                    date=str(info.get("date", "")),
                    city_id=str(info.get("city_id", "")),
                ),
            )
            if sample.width != width or sample.height != height:
                scan.dimension_mismatches += 1
            sample.source_members.append(member)
            sample.boxes.extend(boxes)
            sample.defective_annotations += defective_annotations
            sample.invalid_defective_boxes += invalid_defective_boxes

    scan.duplicate_label_filenames = sum(count - 1 for count in scan.all_label_filenames.values() if count > 1)
    return scan


def index_images(image_zip: Path) -> ImageIndex:
    index = ImageIndex()
    with zipfile.ZipFile(image_zip) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            suffix = Path(info.filename).suffix.lower()
            if suffix not in IMAGE_EXTENSIONS:
                continue
            filename = Path(info.filename).name
            index.image_files += 1
            index.image_filenames[filename] += 1
            index.members.setdefault(filename, ImageMember(member=info.filename, file_size=info.file_size))

    index.duplicate_image_filenames = sum(count - 1 for count in index.image_filenames.values() if count > 1)
    return index


def select_samples(
    samples: dict[str, Sample],
    positive_limit: int,
    include_negatives: bool,
    negative_limit: int,
    seed: int,
) -> list[Sample]:
    if positive_limit < 0 or negative_limit < 0:
        raise ValueError("--positive-limit and --negative-limit must be non-negative")

    rng = random.Random(seed)
    positives = [sample for sample in samples.values() if sample.is_positive]
    negatives = [sample for sample in samples.values() if not sample.is_positive]
    rng.shuffle(positives)
    rng.shuffle(negatives)

    if positive_limit > 0:
        positives = positives[:positive_limit]
    if include_negatives and negative_limit > 0:
        negatives = negatives[:negative_limit]
    elif not include_negatives:
        negatives = []

    selected = positives + negatives
    rng.shuffle(selected)
    return selected


def selected_counts(samples: list[Sample], image_index: ImageIndex) -> Counter[str]:
    counts: Counter[str] = Counter()
    for sample in samples:
        if sample.is_positive:
            counts["positive"] += 1
            counts["boxes"] += len(sample.boxes)
        else:
            counts["negative"] += 1
        image_member = image_index.members.get(sample.filename)
        if image_member:
            counts["matched_images"] += 1
            counts["estimated_image_bytes"] += image_member.file_size
        else:
            counts["missing_images"] += 1
    counts["total"] = len(samples)
    return counts


def sample_names(samples: list[Sample], limit: int, missing_only: ImageIndex | None = None) -> list[str]:
    if limit <= 0:
        return []
    if missing_only is None:
        values = [sample.filename for sample in samples]
    else:
        values = [sample.filename for sample in samples if sample.filename not in missing_only.members]
    return sorted(values)[:limit]


def safe_target_stem(pair_key: str, filename: str) -> str:
    stem = Path(filename).stem
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "_", stem).strip("._-")
    if not safe:
        safe = "image"
    digest = hashlib.sha1(filename.encode("utf-8")).hexdigest()[:10]
    return f"aihub513_{pair_key}_{digest}_{safe[:96]}"


def guard_overwrite(output_dir: Path) -> None:
    resolved = output_dir.resolve()
    cwd = Path.cwd().resolve()
    forbidden = {Path("/").resolve(), cwd, cwd / "runs", cwd / "datasets", Path.home().resolve()}
    if resolved in forbidden:
        raise ValueError(f"Refusing to overwrite broad directory: {resolved}")
    if len(resolved.parts) < 4:
        raise ValueError(f"Refusing to overwrite suspiciously broad directory: {resolved}")


def prepare_output_dir(output_dir: Path, split: str, overwrite: bool) -> None:
    if output_dir.exists():
        if overwrite:
            guard_overwrite(output_dir)
            shutil.rmtree(output_dir)
        elif any(output_dir.iterdir()):
            raise FileExistsError(f"Output directory already exists and is not empty: {output_dir}")

    for split_name in ("train", "val", "test"):
        (output_dir / "images" / split_name).mkdir(parents=True, exist_ok=True)
        (output_dir / "labels" / split_name).mkdir(parents=True, exist_ok=True)


def write_label(sample: Sample, label_path: Path) -> None:
    rows = [
        f"{TARGET_CLASS_ID} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}"
        for x_center, y_center, width, height in sample.boxes
    ]
    label_path.write_text("\n".join(rows) + ("\n" if rows else ""), encoding="utf-8")


def extract_selected_samples(
    samples: list[Sample],
    image_zip: Path,
    image_index: ImageIndex,
    output_dir: Path,
    split: str,
    pair_key: str,
) -> Counter[str]:
    counts: Counter[str] = Counter()
    image_dir = output_dir / "images" / split
    label_dir = output_dir / "labels" / split

    with zipfile.ZipFile(image_zip) as archive:
        for sample in samples:
            image_member = image_index.members.get(sample.filename)
            if not image_member:
                counts["missing_images"] += 1
                continue

            suffix = Path(sample.filename).suffix.lower()
            if not suffix:
                suffix = Path(image_member.member).suffix.lower()
            target_stem = safe_target_stem(pair_key, sample.filename)
            target_image = image_dir / f"{target_stem}{suffix}"
            target_label = label_dir / f"{target_stem}.txt"

            with archive.open(image_member.member) as source_file, target_image.open("wb") as target_file:
                shutil.copyfileobj(source_file, target_file, length=1024 * 1024)
            write_label(sample, target_label)

            counts["images"] += 1
            counts["image_bytes"] += image_member.file_size
            counts["boxes"] += len(sample.boxes)
            if sample.is_positive:
                counts["positive"] += 1
            else:
                counts["negative"] += 1

    return counts


def write_data_yaml(output_dir: Path, split: str) -> None:
    absolute_path = output_dir.resolve()
    lines = [
        f"path: {absolute_path.as_posix()}",
        "train: images/train",
        "val: images/val",
        "test: images/test",
        "",
        "names:",
    ]
    for class_id, class_name in CLASS_NAMES.items():
        lines.append(f"  {class_id}: {class_name}")
    (output_dir / "data.yaml").write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_summary(
    *,
    pair_name: str,
    label_zip: Path,
    image_zip: Path,
    output_dir: Path,
    split: str,
    label_scan: LabelScan,
    image_index: ImageIndex,
    selected: list[Sample],
    selected_counts_: Counter[str],
    build_counts: Counter[str] | None,
    elapsed_seconds: float,
    dry_run: bool,
    include_negatives: bool,
    positive_limit: int,
    negative_limit: int,
    seed: int,
    sample_limit: int,
) -> dict[str, Any]:
    tactile_names = set(label_scan.tactile_samples)
    image_names = set(image_index.members)
    positive_names = {filename for filename, sample in label_scan.tactile_samples.items() if sample.is_positive}
    negative_names = tactile_names - positive_names
    selected_names = {sample.filename for sample in selected}
    missing_selected = selected_names - image_names
    missing_tactile = tactile_names - image_names
    total_boxes = sum(len(sample.boxes) for sample in label_scan.tactile_samples.values())

    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "script": "data_sources/scripts/build_aihub513_validation_subset.py",
        "dry_run": dry_run,
        "pair": pair_name,
        "label_zip": str(label_zip),
        "image_zip": str(image_zip),
        "output_dir": str(output_dir),
        "split": split,
        "elapsed_seconds": round(elapsed_seconds, 3),
        "selection": {
            "include_negatives": include_negatives,
            "positive_limit": positive_limit,
            "negative_limit": negative_limit,
            "seed": seed,
        },
        "label_parsing_rule": {
            "tactile_file": "member path contains 점자블럭 or description.facility == 2_09",
            "tactile_annotation": "annotation.label_name == 점자블럭",
            "positive_box": "annotation.is_defect starts with 불량 and bbox/polygon is valid after clipping",
            "yolo_class": f"{TARGET_CLASS_ID}: {TARGET_CLASS_NAME}",
        },
        "source_counts": {
            "label_json_files": label_scan.label_json_files,
            "label_files_with_filename": label_scan.label_files_with_filename,
            "duplicate_label_filenames": label_scan.duplicate_label_filenames,
            "invalid_label_json_files": label_scan.invalid_label_json_files,
            "image_files": image_index.image_files,
            "duplicate_image_filenames": image_index.duplicate_image_filenames,
            "tactile_label_files": label_scan.tactile_label_files,
            "tactile_unique_filenames": len(tactile_names),
            "tactile_positive_images": len(positive_names),
            "tactile_negative_images": len(negative_names),
            "tactile_boxes": total_boxes,
            "tactile_defective_annotations": label_scan.tactile_defective_annotations,
            "tactile_invalid_defective_boxes": label_scan.tactile_invalid_defective_boxes,
            "tactile_missing_images": len(missing_tactile),
            "dimension_mismatches": label_scan.dimension_mismatches,
        },
        "selected_counts": dict(selected_counts_),
        "build_counts": dict(build_counts or {}),
        "samples": {
            "selected": sample_names(selected, sample_limit),
            "missing_selected_images": sorted(missing_selected)[:sample_limit],
            "missing_tactile_images": sorted(missing_tactile)[:sample_limit],
            "label_parse_errors": label_scan.label_parse_errors[:sample_limit],
        },
        "data_yaml_contract": {
            "labels_written": f"{TARGET_CLASS_ID}: {TARGET_CLASS_NAME} only",
            "names": CLASS_NAMES,
        },
        "image_extraction_policy": "only selected image members are extracted from the VS zip; the full zip is not extracted",
    }


def print_summary(summary: dict[str, Any]) -> None:
    source = summary["source_counts"]
    selected = summary["selected_counts"]
    build = summary["build_counts"]

    print("AI Hub 513 VL/VS tactile validation subset")
    print(f"Pair: {summary['pair']}")
    print(f"Label zip: {summary['label_zip']}")
    print(f"Image zip: {summary['image_zip']}")
    print(f"Output dir: {summary['output_dir']}")
    print(f"Split: {summary['split']}")
    print(f"Elapsed: {summary['elapsed_seconds']:.3f}s")
    print()
    print("Source tactile subset")
    print(f"  Tactile unique filenames: {source['tactile_unique_filenames']:,}")
    print(f"  Positive images: {source['tactile_positive_images']:,}")
    print(f"  Negative images: {source['tactile_negative_images']:,}")
    print(f"  Boxes: {source['tactile_boxes']:,}")
    print(f"  Missing tactile images: {source['tactile_missing_images']:,}")
    if source["invalid_label_json_files"] or source["duplicate_label_filenames"] or source["duplicate_image_filenames"]:
        print(
            "  Notes: "
            f"{source['invalid_label_json_files']:,} invalid JSON, "
            f"{source['duplicate_label_filenames']:,} duplicate label filenames, "
            f"{source['duplicate_image_filenames']:,} duplicate image filenames"
        )
    print()
    print("Selected subset")
    print(f"  Total selected: {selected.get('total', 0):,}")
    print(f"  Positive selected: {selected.get('positive', 0):,}")
    print(f"  Negative selected: {selected.get('negative', 0):,}")
    print(f"  Boxes selected: {selected.get('boxes', 0):,}")
    print(f"  Matched selected images: {selected.get('matched_images', 0):,}")
    print(f"  Missing selected images: {selected.get('missing_images', 0):,}")
    print(f"  Estimated selected image bytes: {selected.get('estimated_image_bytes', 0):,}")
    if build:
        print()
        print("Build output")
        print(f"  Images written: {build.get('images', 0):,}")
        print(f"  Positive labels: {build.get('positive', 0):,}")
        print(f"  Negative labels: {build.get('negative', 0):,}")
        print(f"  Boxes written: {build.get('boxes', 0):,}")
        print(f"  Image bytes written: {build.get('image_bytes', 0):,}")


def write_json_summary(path: Path, summary: dict[str, Any]) -> None:
    path = path.expanduser()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_build_summary(output_dir: Path, summary: dict[str, Any]) -> None:
    source = summary["source_counts"]
    selected = summary["selected_counts"]
    build = summary["build_counts"]
    lines = [
        "# AI Hub 513 tactile validation subset",
        "",
        f"Generated: `{summary['generated_at']}`",
        f"Pair: `{summary['pair']}`",
        f"Label zip: `{summary['label_zip']}`",
        f"Image zip: `{summary['image_zip']}`",
        "",
        "Only defective `점자블럭` annotations are written as class `0 damaged_tactile_block` labels.",
        "Normal tactile images, when included, are written as empty-label hard negatives.",
        "",
        "## Counts",
        "",
        "| item | count |",
        "| --- | ---: |",
        f"| source tactile filenames | {source['tactile_unique_filenames']} |",
        f"| source positive images | {source['tactile_positive_images']} |",
        f"| source negative images | {source['tactile_negative_images']} |",
        f"| source boxes | {source['tactile_boxes']} |",
        f"| selected images | {selected.get('total', 0)} |",
        f"| selected positives | {selected.get('positive', 0)} |",
        f"| selected negatives | {selected.get('negative', 0)} |",
        f"| selected boxes | {selected.get('boxes', 0)} |",
        f"| written images | {build.get('images', 0)} |",
        f"| written boxes | {build.get('boxes', 0)} |",
        "",
        "## Data YAML",
        "",
        "`data.yaml` preserves the v2 4-class contract, but this subset writes labels for class `0` only.",
        "",
        "## Extraction",
        "",
        "Only selected image members were extracted from the VS zip. The full source zip was not extracted.",
    ]
    (output_dir / "BUILD_SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    started = time.monotonic()

    try:
        label_zip, image_zip, pair_name, pair_key = resolve_pair(args)
        output_dir = args.output_dir or default_output_dir(pair_key)

        label_scan = read_tactile_samples(label_zip, quiet=args.quiet)
        image_index = index_images(image_zip)
        selected = select_samples(
            samples=label_scan.tactile_samples,
            positive_limit=args.positive_limit,
            include_negatives=args.include_negatives,
            negative_limit=args.negative_limit,
            seed=args.seed,
        )
        selected_counts_ = selected_counts(selected, image_index)

        build_counts: Counter[str] | None = None
        if not args.dry_run:
            prepare_output_dir(output_dir, args.split, args.overwrite)
            build_counts = extract_selected_samples(
                samples=selected,
                image_zip=image_zip,
                image_index=image_index,
                output_dir=output_dir,
                split=args.split,
                pair_key=pair_key,
            )
            write_data_yaml(output_dir, args.split)

        summary = build_summary(
            pair_name=pair_name,
            label_zip=label_zip,
            image_zip=image_zip,
            output_dir=output_dir,
            split=args.split,
            label_scan=label_scan,
            image_index=image_index,
            selected=selected,
            selected_counts_=selected_counts_,
            build_counts=build_counts,
            elapsed_seconds=time.monotonic() - started,
            dry_run=args.dry_run,
            include_negatives=args.include_negatives,
            positive_limit=args.positive_limit,
            negative_limit=args.negative_limit,
            seed=args.seed,
            sample_limit=args.sample_limit,
        )

        print_summary(summary)
        if not args.dry_run:
            write_build_summary(output_dir, summary)
        if args.json_summary:
            write_json_summary(args.json_summary, summary)
            print()
            print(f"Wrote JSON summary: {args.json_summary.expanduser()}")
    except (FileNotFoundError, FileExistsError, ValueError, zipfile.BadZipFile) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    return 0 if selected_counts_.get("missing_images", 0) == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
