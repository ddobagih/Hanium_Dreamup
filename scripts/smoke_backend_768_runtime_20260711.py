#!/usr/bin/env python3
"""Load and invoke the canonical 768 backend detector on its warm-up PNG."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.app.services.detect_v2 import LazyYoloDetectV2Runtime


DEFAULT_MODEL = (
    ROOT
    / "model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708"
    / "walksafe_13cls_yolo26n_img768_best_epoch270.pt"
)
DEFAULT_CONFIG = ROOT / "configs/walksafe_unified_epoch270_field_20260711.json"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args()

    runtime = LazyYoloDetectV2Runtime(
        unified_model_path=args.model.resolve(),
        runtime_config_path=args.config.resolve(),
        unified_image_size=768,
    )
    runtime.warmup()
    print(json.dumps({"ok": True, "model": args.model.name, "image_size": 768, "class_count": 13}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
