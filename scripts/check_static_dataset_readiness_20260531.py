#!/usr/bin/env python3
"""Check what can be evaluated from local static-image artifacts right now.

The selected reviewed tactile dataset is intentionally not bundled in this
working tree.  This check separates "ready to run now" from "blocked until the
static image dataset is restored", so we do not overstate model evidence.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

DEFAULT_REVIEWED_DATASET = REPO_ROOT / "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522"
DEFAULT_PRED_LABELS = REPO_ROOT / "runs/predict/stage1_yolo26s_reviewed_test_labels_20260523_203422/labels"
DEFAULT_PRESENCE_SUMMARY = REPO_ROOT / "reports/runs/evaluations/stage1_yolo26s_reviewed_test_presence_20260523_203422/SUMMARY.md"
DEFAULT_CUSTOM_MODEL = (
    REPO_ROOT
    / "runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt"
)
DEFAULT_COCO_MODEL = REPO_ROOT / "model/artifacts/pretrained/yolo26n.pt"
DEFAULT_ANDROID_CUSTOM_TFLITE = REPO_ROOT / "apps/android/app/src/main/assets/models/custom_tactile_yolo26s_float32.tflite"
DEFAULT_ANDROID_COCO_TFLITE = REPO_ROOT / "apps/android/app/src/main/assets/models/coco_yolo26n_float32.tflite"


@dataclass(frozen=True)
class SplitCount:
    images: int
    labels: int
    boxes: int


@dataclass(frozen=True)
class DatasetReadiness:
    path: str
    exists: bool
    splits: dict[str, SplitCount]


@dataclass(frozen=True)
class ArtifactReadiness:
    path: str
    exists: bool
    file_count: int | None = None
    size_bytes: int | None = None


@dataclass(frozen=True)
class StaticReadinessResult:
    ok: bool
    status: str
    blockers: list[str]
    ready_now: list[str]
    datasets: dict[str, DatasetReadiness]
    artifacts: dict[str, ArtifactReadiness]
    next_commands: list[str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check static-image dataset/model artifact readiness.")
    parser.add_argument("--reviewed-dataset", type=Path, default=DEFAULT_REVIEWED_DATASET)
    return parser.parse_args()


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def count_label_boxes(label_dir: Path) -> int:
    if not label_dir.exists():
        return 0
    boxes = 0
    for label in label_dir.glob("*.txt"):
        for line in label.read_text(encoding="utf-8").splitlines():
            if line.strip():
                boxes += 1
    return boxes


def dataset_readiness(path: Path) -> DatasetReadiness:
    splits: dict[str, SplitCount] = {}
    for split in ("train", "val", "test"):
        image_dir = path / "images" / split
        label_dir = path / "labels" / split
        image_count = (
            sum(1 for p in image_dir.iterdir() if p.is_file() and p.suffix.lower() in IMAGE_SUFFIXES)
            if image_dir.exists()
            else 0
        )
        label_count = sum(1 for p in label_dir.glob("*.txt") if p.is_file()) if label_dir.exists() else 0
        splits[split] = SplitCount(images=image_count, labels=label_count, boxes=count_label_boxes(label_dir))
    return DatasetReadiness(path=rel(path), exists=path.exists(), splits=splits)


def artifact_readiness(path: Path, *, count_files: bool = False) -> ArtifactReadiness:
    exists = path.exists()
    file_count: int | None = None
    size_bytes: int | None = None
    if exists and count_files and path.is_dir():
        file_count = sum(1 for p in path.iterdir() if p.is_file())
    if exists and path.is_file():
        size_bytes = path.stat().st_size
    return ArtifactReadiness(path=rel(path), exists=exists, file_count=file_count, size_bytes=size_bytes)


def has_nonempty_test(dataset: DatasetReadiness) -> bool:
    test = dataset.splits["test"]
    return dataset.exists and test.images > 0 and test.labels > 0


def run_check(args: argparse.Namespace) -> StaticReadinessResult:
    datasets = {
        "selected_reviewed_tactile3": dataset_readiness(args.reviewed_dataset),
        "repo_walksafe_kr_v1": dataset_readiness(REPO_ROOT / "datasets/walksafe_kr_v1"),
        "repo_walksafe_v1": dataset_readiness(REPO_ROOT / "datasets/walksafe_v1"),
    }
    artifacts = {
        "custom_stage1_pt": artifact_readiness(DEFAULT_CUSTOM_MODEL),
        "coco_pt": artifact_readiness(DEFAULT_COCO_MODEL),
        "android_custom_tflite": artifact_readiness(DEFAULT_ANDROID_CUSTOM_TFLITE),
        "android_coco_tflite": artifact_readiness(DEFAULT_ANDROID_COCO_TFLITE),
        "saved_stage1_prediction_labels": artifact_readiness(DEFAULT_PRED_LABELS, count_files=True),
        "saved_presence_summary": artifact_readiness(DEFAULT_PRESENCE_SUMMARY),
    }

    blockers: list[str] = []
    ready_now: list[str] = []
    selected = datasets["selected_reviewed_tactile3"]
    if has_nonempty_test(selected):
        ready_now.append("selected reviewed tactile3 test split is present; YOLO val/presence evaluation can run.")
    else:
        blockers.append(
            "selected reviewed tactile3 dataset is not present with test images/labels; full static-image metrics cannot be rerun."
        )

    if artifacts["saved_stage1_prediction_labels"].exists and artifacts["saved_presence_summary"].exists:
        ready_now.append("saved Stage1 prediction labels and presence sweep summary are available for review.")
    else:
        blockers.append("saved Stage1 prediction labels or presence summary are missing.")

    if artifacts["custom_stage1_pt"].exists and artifacts["coco_pt"].exists:
        ready_now.append("backend/server model checkpoints exist locally.")
    else:
        blockers.append("one or more backend/server model checkpoints are missing.")

    if artifacts["android_custom_tflite"].exists and artifacts["android_coco_tflite"].exists:
        ready_now.append("Android TFLite model assets exist locally.")
    else:
        blockers.append("one or more Android TFLite model assets are missing.")

    if has_nonempty_test(selected):
        next_commands = [
            "python data_sources/scripts/validate_yolo_dataset.py datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/data.yaml",
            "python scripts/evaluate_yolo_image_level_presence_20260523.py --gt-label-dir datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/labels/test --pred-label-dir runs/predict/stage1_yolo26s_reviewed_test_labels_20260523_203422/labels --image-dir datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test --data-yaml datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/data.yaml --target-class-name damaged_tactile_block --confidence-threshold 0.50",
        ]
    else:
        next_commands = [
            "python scripts/check_android_tflite_contract_20260531.py",
            "sed -n '1,120p' reports/runs/evaluations/stage1_yolo26s_reviewed_test_presence_20260523_203422/SUMMARY.md",
        ]

    ok = not blockers or all("full static-image metrics cannot be rerun" not in blocker for blocker in blockers)
    status = "ready_for_full_static_eval" if has_nonempty_test(selected) else "blocked_for_full_static_eval_dataset_missing"
    return StaticReadinessResult(
        ok=ok,
        status=status,
        blockers=blockers,
        ready_now=ready_now,
        datasets=datasets,
        artifacts=artifacts,
        next_commands=next_commands,
    )


def main() -> int:
    args = parse_args()
    result = run_check(args)
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    # Missing reviewed dataset is a truthful readiness result, not a script error.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
