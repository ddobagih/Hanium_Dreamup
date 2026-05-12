#!/usr/bin/env python3
"""Build the walksafe_v1 YOLO dataset from downloaded public sources."""

from __future__ import annotations

import argparse
import random
import shutil
from collections import Counter
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
TARGET_CLASSES = {
    0: "damaged_tactile_block",
    1: "parked_kickboard_bicycle",
    2: "construction_obstacle",
    3: "pothole",
}


def reset_target_dataset(dataset_root: Path) -> None:
    for split in ("train", "val", "test"):
        for kind in ("images", "labels"):
            directory = dataset_root / kind / split
            directory.mkdir(parents=True, exist_ok=True)
            for path in directory.iterdir():
                if path.name == ".gitkeep":
                    continue
                if path.is_file():
                    path.unlink()

    for cache_file in (dataset_root / "labels").glob("*.cache"):
        cache_file.unlink()


def read_yolo_labels(path: Path, class_map: dict[int, int]) -> list[str]:
    rows: list[str] = []
    if not path.exists():
        return rows

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        parts = raw_line.strip().split()
        if len(parts) != 5:
            continue

        try:
            source_class = int(parts[0])
        except ValueError:
            continue

        if source_class not in class_map:
            continue

        try:
            coords = [float(value) for value in parts[1:]]
        except ValueError:
            continue

        if any(value < 0 or value > 1 for value in coords):
            continue
        if coords[2] <= 0 or coords[3] <= 0:
            continue

        rows.append(" ".join([str(class_map[source_class]), *parts[1:]]))

    return rows


def copy_yolo_sample(
    image_path: Path,
    label_rows: list[str],
    target_root: Path,
    split: str,
    prefix: str,
) -> Counter:
    target_stem = f"{prefix}_{image_path.stem}"
    target_image = target_root / "images" / split / f"{target_stem}{image_path.suffix.lower()}"
    target_label = target_root / "labels" / split / f"{target_stem}.txt"

    shutil.copy2(image_path, target_image)
    target_label.write_text("\n".join(label_rows) + "\n", encoding="utf-8")

    return Counter(int(row.split()[0]) for row in label_rows)


def split_samples(samples: list[tuple[Path, list[str]]], seed: int) -> dict[str, list[tuple[Path, list[str]]]]:
    shuffled = samples[:]
    random.Random(seed).shuffle(shuffled)
    train_end = int(len(shuffled) * 0.7)
    val_end = int(len(shuffled) * 0.9)
    return {
        "train": shuffled[:train_end],
        "val": shuffled[train_end:val_end],
        "test": shuffled[val_end:],
    }


def convert_split_yolo_source(
    source_root: Path,
    target_root: Path,
    class_map: dict[int, int],
    prefix: str,
    seed: int,
) -> Counter:
    counts: Counter = Counter()
    samples: list[tuple[Path, list[str]]] = []

    for source_split in ("train", "valid", "test"):
        image_dir = source_root / source_split / "images"
        label_dir = source_root / source_split / "labels"
        if not image_dir.exists() or not label_dir.exists():
            continue

        for image_path in sorted(image_dir.iterdir()):
            if not image_path.is_file() or image_path.suffix.lower() not in IMAGE_EXTENSIONS:
                continue

            label_rows = read_yolo_labels(label_dir / f"{image_path.stem}.txt", class_map)
            if not label_rows:
                continue

            samples.append((image_path, label_rows))

    for target_split, split_samples_ in split_samples(samples, seed).items():
        for image_path, label_rows in split_samples_:
            counts.update(copy_yolo_sample(image_path, label_rows, target_root, target_split, prefix))

    return counts


def split_pothole_images(images: list[Path], seed: int) -> dict[str, list[Path]]:
    shuffled = images[:]
    random.Random(seed).shuffle(shuffled)
    train_end = int(len(shuffled) * 0.7)
    val_end = int(len(shuffled) * 0.9)
    return {
        "train": shuffled[:train_end],
        "val": shuffled[train_end:val_end],
        "test": shuffled[val_end:],
    }


def convert_pothole_source(source_root: Path, target_root: Path, seed: int) -> Counter:
    dataset_dir = source_root / "Pothole Dataset"
    images = [
        path
        for path in sorted(dataset_dir.iterdir())
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS and (dataset_dir / f"{path.stem}.txt").exists()
    ]

    counts: Counter = Counter()
    for split, split_images in split_pothole_images(images, seed).items():
        for image_path in split_images:
            label_rows = read_yolo_labels(dataset_dir / f"{image_path.stem}.txt", {0: 3})
            if not label_rows:
                continue
            counts.update(copy_yolo_sample(image_path, label_rows, target_root, split, "pothole_ivcnz"))

    return counts


def write_summary(target_root: Path, counts: Counter) -> None:
    lines = [
        "# walksafe_v1 dataset build summary",
        "",
        "Generated by `data_sources/scripts/build_walksafe_v1.py`.",
        "",
        "| class_id | class_name | boxes |",
        "| --- | --- | --- |",
    ]

    for class_id, class_name in TARGET_CLASSES.items():
        lines.append(f"| {class_id} | `{class_name}` | {counts.get(class_id, 0)} |")

    lines.append("")
    lines.append("Note: class 0 is expected to remain empty until tactile block data is collected manually.")
    (target_root / "BUILD_SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the walksafe_v1 dataset.")
    parser.add_argument("--target", default="datasets/walksafe_v1")
    parser.add_argument("--raw-root", default="data_sources/raw")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    target_root = Path(args.target)
    raw_root = Path(args.raw_root)

    reset_target_dataset(target_root)

    counts: Counter = Counter()
    counts.update(
        convert_split_yolo_source(
            raw_root / "road_traffic",
            target_root,
            class_map={0: 1},
            prefix="road_traffic",
            seed=args.seed + 1,
        )
    )
    counts.update(
        convert_split_yolo_source(
            raw_root / "street_work",
            target_root,
            class_map={0: 2},
            prefix="street_work",
            seed=args.seed + 2,
        )
    )
    counts.update(convert_pothole_source(raw_root / "pothole_ivcnz", target_root, seed=args.seed))

    write_summary(target_root, counts)

    print("Built walksafe_v1 dataset:")
    for class_id, class_name in TARGET_CLASSES.items():
        print(f"  {class_id} {class_name}: {counts.get(class_id, 0)} boxes")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
