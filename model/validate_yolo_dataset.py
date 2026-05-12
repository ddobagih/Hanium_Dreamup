#!/usr/bin/env python3
"""Validate a YOLO dataset without third-party dependencies."""

from __future__ import annotations

import argparse
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


def parse_data_yaml(path: Path) -> dict:
    config: dict[str, object] = {"names": {}}
    in_names = False

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].rstrip()
        if not line:
            continue

        if line.startswith("names:"):
            in_names = True
            continue

        if in_names and raw_line.startswith("  "):
            key, value = line.strip().split(":", 1)
            config["names"][int(key.strip())] = value.strip()
            continue

        in_names = False
        if ":" in line:
            key, value = line.split(":", 1)
            config[key.strip()] = value.strip()

    return config


def find_images(path: Path) -> dict[str, Path]:
    return {
        image_path.stem: image_path
        for image_path in sorted(path.iterdir())
        if image_path.is_file() and image_path.suffix.lower() in IMAGE_EXTENSIONS
    }


def find_labels(path: Path) -> dict[str, Path]:
    return {
        label_path.stem: label_path
        for label_path in sorted(path.glob("*.txt"))
        if label_path.is_file()
    }


def validate_label_file(path: Path, class_ids: set[int]) -> list[str]:
    errors: list[str] = []

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line:
            continue

        parts = line.split()
        if len(parts) != 5:
            errors.append(f"{path}:{line_number}: expected 5 values, got {len(parts)}")
            continue

        try:
            class_id = int(parts[0])
        except ValueError:
            errors.append(f"{path}:{line_number}: class_id must be an integer")
            continue

        if class_id not in class_ids:
            errors.append(f"{path}:{line_number}: unknown class_id {class_id}")

        try:
            coords = [float(value) for value in parts[1:]]
        except ValueError:
            errors.append(f"{path}:{line_number}: bbox values must be numbers")
            continue

        if any(value < 0 or value > 1 for value in coords):
            errors.append(f"{path}:{line_number}: bbox values must be between 0 and 1")

        if coords[2] <= 0 or coords[3] <= 0:
            errors.append(f"{path}:{line_number}: bbox width and height must be greater than 0")

    return errors


def validate_dataset(data_yaml: Path) -> int:
    errors: list[str] = []
    warnings: list[str] = []

    if not data_yaml.exists():
        print(f"ERROR: data file not found: {data_yaml}")
        return 1

    config = parse_data_yaml(data_yaml)
    names = config.get("names", {})
    if not isinstance(names, dict) or not names:
        print(f"ERROR: no class names found in {data_yaml}")
        return 1

    configured_root = Path(str(config.get("path", ".")))
    if configured_root.is_absolute():
        dataset_root = configured_root
    elif (Path.cwd() / configured_root).exists():
        dataset_root = Path.cwd() / configured_root
    else:
        dataset_root = data_yaml.parent / configured_root
    dataset_root = dataset_root.resolve()
    class_ids = set(names.keys())

    print(f"Dataset: {dataset_root}")
    print("Classes:")
    for class_id, name in sorted(names.items()):
        print(f"  {class_id}: {name}")

    for split in ("train", "val", "test"):
        image_dir = dataset_root / str(config.get(split, f"images/{split}"))
        label_dir = dataset_root / "labels" / split

        if not image_dir.exists():
            errors.append(f"{split}: image directory not found: {image_dir}")
            continue
        if not label_dir.exists():
            errors.append(f"{split}: label directory not found: {label_dir}")
            continue

        images = find_images(image_dir)
        labels = find_labels(label_dir)

        missing_labels = sorted(set(images) - set(labels))
        orphan_labels = sorted(set(labels) - set(images))

        for image_stem in missing_labels:
            errors.append(f"{split}: missing label for image {images[image_stem].name}")
        for label_stem in orphan_labels:
            warnings.append(f"{split}: label has no matching image: {labels[label_stem].name}")

        for label_path in labels.values():
            errors.extend(validate_label_file(label_path, class_ids))

        if not images:
            warnings.append(f"{split}: no images found yet")

        print(f"{split}: {len(images)} images, {len(labels)} labels")

    if warnings:
        print("\nWarnings:")
        for warning in warnings:
            print(f"  - {warning}")

    if errors:
        print("\nErrors:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("\nDataset structure is valid.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate a YOLO dataset.")
    parser.add_argument(
        "--data",
        default="datasets/walksafe_kr_v1/data.yaml",
        help="Path to the YOLO data.yaml file.",
    )
    args = parser.parse_args()

    return validate_dataset(Path(args.data))


if __name__ == "__main__":
    raise SystemExit(main())
