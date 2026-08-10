#!/usr/bin/env python3
"""Build the Korea-target tactile block YOLO dataset from AI Hub 513 files."""

from __future__ import annotations

import argparse
import json
import random
import shutil
import zipfile
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
TARGET_CLASS_ID = 0
TARGET_CLASS_NAME = "damaged_tactile_block"


@dataclass
class Sample:
    filename: str
    width: int
    height: int
    source_label_zip: str
    date: str = ""
    city_id: str = ""
    boxes: list[tuple[float, float, float, float]] = field(default_factory=list)

    @property
    def is_positive(self) -> bool:
        return bool(self.boxes)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build walksafe_kr_v1 from AI Hub tactile block labels/images.")
    parser.add_argument("--download-root", default=str(Path.home() / "Downloads"))
    parser.add_argument("--target", default="datasets/walksafe_kr_v1")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--max-positive", type=int, default=3000, help="0 means use all positive samples.")
    parser.add_argument("--max-negative", type=int, default=3000, help="0 means use all negative samples.")
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--test-ratio", type=float, default=0.1)
    parser.add_argument("--no-reset", action="store_true", help="Do not clear existing target images/labels first.")
    parser.add_argument("--dry-run", action="store_true", help="Scan labels and image zips without writing the dataset.")
    return parser.parse_args()


def find_zip(download_root: Path, name: str) -> Path:
    matches = sorted(download_root.rglob(name))
    if not matches:
        raise FileNotFoundError(f"Could not find {name} under {download_root}")
    return matches[0]


def label_zips(download_root: Path) -> list[Path]:
    return [find_zip(download_root, "TL8.zip"), find_zip(download_root, "TL9.zip")]


def source_zips(download_root: Path) -> list[Path]:
    return [find_zip(download_root, "TS8.zip"), find_zip(download_root, "TS9.zip")]


def bbox_from_annotation(annotation: dict, width: int, height: int) -> tuple[float, float, float, float] | None:
    points = annotation.get("annotation_info")
    if not points:
        return None

    annotation_type = annotation.get("annotation_type")
    if annotation_type == "bbox":
        bbox = points[0] if isinstance(points, list) and points else points
        if not isinstance(bbox, list) or len(bbox) < 4:
            return None
        x_min, y_min, box_width, box_height = (float(value) for value in bbox[:4])
        x_max = x_min + box_width
        y_max = y_min + box_height
    else:
        polygon_points = points
        if not isinstance(polygon_points, list):
            return None
        xs: list[float] = []
        ys: list[float] = []
        for point in polygon_points:
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


def read_tactile_samples(label_zip_paths: list[Path]) -> dict[str, Sample]:
    samples: dict[str, Sample] = {}

    for label_zip_path in label_zip_paths:
        with zipfile.ZipFile(label_zip_path) as archive:
            for member in archive.namelist():
                if not member.lower().endswith(".json"):
                    continue

                with archive.open(member) as file:
                    data = json.load(file)

                info = data.get("info", {})
                description = data.get("description", {})
                filename = str(info.get("filename", "")).strip()
                width = int(info.get("width", 0) or 0)
                height = int(info.get("height", 0) or 0)
                if not filename or width <= 0 or height <= 0:
                    continue

                is_tactile_file = "점자블럭" in member or description.get("facility") == "2_09"
                boxes: list[tuple[float, float, float, float]] = []
                has_tactile_annotation = False

                for annotation in data.get("annotations", []):
                    if annotation.get("label_name") != "점자블럭":
                        continue
                    has_tactile_annotation = True
                    if str(annotation.get("is_defect", "")).startswith("불량"):
                        bbox = bbox_from_annotation(annotation, width, height)
                        if bbox:
                            boxes.append(bbox)

                if not is_tactile_file and not has_tactile_annotation:
                    continue

                sample = samples.setdefault(
                    filename,
                    Sample(
                        filename=filename,
                        width=width,
                        height=height,
                        source_label_zip=label_zip_path.name,
                        date=str(info.get("date", "")),
                        city_id=str(info.get("city_id", "")),
                    ),
                )
                sample.boxes.extend(boxes)

    return samples


def sample_balanced(samples: dict[str, Sample], max_positive: int, max_negative: int, seed: int) -> list[Sample]:
    rng = random.Random(seed)
    positives = [sample for sample in samples.values() if sample.is_positive]
    negatives = [sample for sample in samples.values() if not sample.is_positive]
    rng.shuffle(positives)
    rng.shuffle(negatives)

    if max_positive > 0:
        positives = positives[:max_positive]
    if max_negative > 0:
        negatives = negatives[:max_negative]

    selected = positives + negatives
    rng.shuffle(selected)
    return selected


def split_samples(samples: list[Sample], val_ratio: float, test_ratio: float, seed: int) -> dict[str, list[Sample]]:
    if val_ratio < 0 or test_ratio < 0 or val_ratio + test_ratio >= 1:
        raise ValueError("val_ratio and test_ratio must be non-negative and sum to less than 1")

    positives = [sample for sample in samples if sample.is_positive]
    negatives = [sample for sample in samples if not sample.is_positive]
    rng = random.Random(seed)
    rng.shuffle(positives)
    rng.shuffle(negatives)

    splits = {"train": [], "val": [], "test": []}
    for bucket in (positives, negatives):
        test_count = int(len(bucket) * test_ratio)
        val_count = int(len(bucket) * val_ratio)
        splits["test"].extend(bucket[:test_count])
        splits["val"].extend(bucket[test_count : test_count + val_count])
        splits["train"].extend(bucket[test_count + val_count :])

    for split_samples_ in splits.values():
        rng.shuffle(split_samples_)

    return splits


def index_images(source_zip_paths: list[Path]) -> dict[str, tuple[Path, str]]:
    image_index: dict[str, tuple[Path, str]] = {}
    for source_zip_path in source_zip_paths:
        with zipfile.ZipFile(source_zip_path) as archive:
            for member in archive.namelist():
                suffix = Path(member).suffix.lower()
                if suffix not in IMAGE_EXTENSIONS:
                    continue
                image_index.setdefault(Path(member).name, (source_zip_path, member))
    return image_index


def reset_target_dataset(target_root: Path) -> None:
    for split in ("train", "val", "test"):
        for kind in ("images", "labels"):
            directory = target_root / kind / split
            directory.mkdir(parents=True, exist_ok=True)
            for path in directory.iterdir():
                if path.name == ".gitkeep":
                    continue
                if path.is_file():
                    path.unlink()

    for cache_file in (target_root / "labels").glob("**/*.cache"):
        cache_file.unlink()


def write_label(sample: Sample, label_path: Path) -> None:
    rows = [
        f"{TARGET_CLASS_ID} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}"
        for x_center, y_center, width, height in sample.boxes
    ]
    label_path.write_text("\n".join(rows) + ("\n" if rows else ""), encoding="utf-8")


def copy_samples(splits: dict[str, list[Sample]], image_index: dict[str, tuple[Path, str]], target_root: Path) -> Counter:
    counts: Counter = Counter()
    open_archives: dict[Path, zipfile.ZipFile] = {}

    try:
        for split, samples in splits.items():
            for sample in samples:
                if sample.filename not in image_index:
                    counts["missing_images"] += 1
                    continue

                source_zip_path, member = image_index[sample.filename]
                archive = open_archives.get(source_zip_path)
                if archive is None:
                    archive = zipfile.ZipFile(source_zip_path)
                    open_archives[source_zip_path] = archive

                source_suffix = Path(sample.filename).suffix.lower()
                target_stem = f"aihub513_tactile_{Path(sample.filename).stem}"
                target_image = target_root / "images" / split / f"{target_stem}{source_suffix}"
                target_label = target_root / "labels" / split / f"{target_stem}.txt"

                with archive.open(member) as source_file, target_image.open("wb") as target_file:
                    shutil.copyfileobj(source_file, target_file)
                write_label(sample, target_label)

                counts[f"{split}_images"] += 1
                counts[f"{split}_boxes"] += len(sample.boxes)
                if sample.is_positive:
                    counts[f"{split}_positive"] += 1
                else:
                    counts[f"{split}_negative"] += 1
    finally:
        for archive in open_archives.values():
            archive.close()

    return counts


def write_summary(target_root: Path, label_zip_paths: list[Path], source_zip_paths: list[Path], counts: Counter) -> None:
    dataset_name = target_root.name
    lines = [
        f"# {dataset_name} tactile block build summary",
        "",
        "Generated by `data_sources/scripts/build_walksafe_kr_tactile.py`.",
        "",
        "## Sources",
        "",
        "| type | file |",
        "| --- | --- |",
    ]
    for path in label_zip_paths:
        lines.append(f"| label | `{path}` |")
    for path in source_zip_paths:
        lines.append(f"| image | `{path}` |")

    lines.extend(
        [
            "",
            "## Counts",
            "",
            "| split | images | positive images | negative images | boxes |",
            "| --- | ---: | ---: | ---: | ---: |",
        ]
    )
    for split in ("train", "val", "test"):
        lines.append(
            f"| {split} | {counts.get(f'{split}_images', 0)} | "
            f"{counts.get(f'{split}_positive', 0)} | "
            f"{counts.get(f'{split}_negative', 0)} | "
            f"{counts.get(f'{split}_boxes', 0)} |"
        )

    lines.extend(
        [
            "",
            f"Target class: `{TARGET_CLASS_ID}: {TARGET_CLASS_NAME}`",
            "",
            "Normal tactile block images are kept as negative images with empty label files.",
        ]
    )
    if counts.get("missing_images", 0):
        lines.append(f"Missing images skipped: {counts['missing_images']}")

    (target_root / "BUILD_SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    download_root = Path(args.download_root).expanduser()
    target_root = Path(args.target)

    label_zip_paths = label_zips(download_root)
    source_zip_paths = source_zips(download_root)

    print("Using label zips:")
    for path in label_zip_paths:
        print(f"  {path}")
    print("Using source zips:")
    for path in source_zip_paths:
        print(f"  {path}")

    samples = read_tactile_samples(label_zip_paths)
    selected = sample_balanced(samples, args.max_positive, args.max_negative, args.seed)
    positives = sum(1 for sample in selected if sample.is_positive)
    negatives = len(selected) - positives
    boxes = sum(len(sample.boxes) for sample in selected)

    print(f"Found tactile samples: {len(samples)}")
    print(f"Selected samples: {len(selected)} ({positives} positive, {negatives} negative, {boxes} boxes)")

    image_index = index_images(source_zip_paths)
    missing = [sample.filename for sample in selected if sample.filename not in image_index]
    print(f"Indexed source images: {len(image_index)}")
    print(f"Missing selected images: {len(missing)}")

    if args.dry_run:
        return 0 if not missing else 1

    if not args.no_reset:
        reset_target_dataset(target_root)

    splits = split_samples(selected, args.val_ratio, args.test_ratio, args.seed)
    counts = copy_samples(splits, image_index, target_root)
    write_summary(target_root, label_zip_paths, source_zip_paths, counts)

    print(f"Built {target_root.name} tactile dataset:")
    for split in ("train", "val", "test"):
        print(
            f"  {split}: {counts.get(f'{split}_images', 0)} images, "
            f"{counts.get(f'{split}_positive', 0)} positive, "
            f"{counts.get(f'{split}_negative', 0)} negative, "
            f"{counts.get(f'{split}_boxes', 0)} boxes"
        )

    if counts.get("missing_images", 0):
        print(f"WARNING: skipped {counts['missing_images']} samples because images were missing")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
