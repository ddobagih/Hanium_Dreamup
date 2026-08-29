#!/usr/bin/env python3
"""Train the Korea-target YOLO model."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))
from scripts.walksafe_dataset_integrity import (
    DatasetIntegrityError,
    verify_approved_dataset_gate,
    verify_content_hashed_manifest,
    verify_current_approved_dataset_database,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train a YOLO baseline for the Korea-target dataset.")
    parser.add_argument(
        "--data",
        required=True,
        help="Path to the intended YOLO data.yaml. No dataset is selected implicitly.",
    )
    parser.add_argument("--model", default="model/artifacts/pretrained/yolo11n.pt")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--device", default=None)
    parser.add_argument("--project", default="runs/detect")
    parser.add_argument("--name", default="walksafe_kr_v1_baseline")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the resolved training configuration without importing ultralytics.",
    )
    parser.add_argument(
        "--dataset-manifest",
        help="Content-hashed materialized dataset manifest.",
    )
    parser.add_argument(
        "--approved-dataset-gate",
        help="Short-lived Backend-issued approved dataset gate.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    project_root = PROJECT_ROOT
    data_path = Path(args.data)
    if not data_path.is_absolute():
        data_path = project_root / data_path

    if not data_path.exists():
        print(f"ERROR: data file not found: {data_path}")
        return 1

    if not args.dataset_manifest or not args.approved_dataset_gate:
        print("ERROR: a dataset manifest and current approved dataset gate are required")
        return 1
    manifest_path = Path(args.dataset_manifest)
    gate_path = Path(args.approved_dataset_gate)
    if not manifest_path.is_absolute():
        manifest_path = project_root / manifest_path
    if not gate_path.is_absolute():
        gate_path = project_root / gate_path
    try:
        verify_content_hashed_manifest(
            manifest_path, repository_root=project_root
        )
        gate = verify_approved_dataset_gate(
            gate_path, manifest_path=manifest_path
        )
        verify_current_approved_dataset_database(
            gate,
            database_url=os.getenv(
                "WALKSAFE_TRAINING_LIFECYCLE_DATABASE_URL", ""
            ).strip(),
        )
    except DatasetIntegrityError as exc:
        print(f"ERROR: approved dataset gate failed: {exc}")
        return 1

    os.chdir(project_root)

    print("Training configuration:")
    print(f"  data: {data_path}")
    print(f"  model: {args.model}")
    print(f"  epochs: {args.epochs}")
    print(f"  imgsz: {args.imgsz}")
    print(f"  batch: {args.batch}")
    print(f"  device: {args.device or 'auto'}")
    print(f"  project: {args.project}")
    print(f"  name: {args.name}")

    if args.dry_run:
        return 0

    try:
        from ultralytics import YOLO
    except ImportError:
        print("ERROR: ultralytics is not installed.")
        print("Install model dependencies with: python -m pip install -r model/requirements.txt")
        return 1

    model = YOLO(args.model)
    project_path = Path(args.project)
    if not project_path.is_absolute():
        project_path = project_root / project_path

    model.train(
        data=str(data_path),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        project=str(project_path),
        name=args.name,
    )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
