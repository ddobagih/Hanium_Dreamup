#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CUSTOM = ROOT / "runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt"
DEFAULT_COCO = ROOT / "yolo26n.pt"
DEFAULT_OUTPUT = ROOT / "apps/android/app/src/main/assets/models"
CONFIG_PATH = ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"


def python_with_ultralytics() -> str:
    candidates = [Path(sys.executable), ROOT / ".venv/bin/python"]
    for candidate in candidates:
        if not candidate.exists():
            continue
        result = subprocess.run(
            [str(candidate), "-c", "import ultralytics"],
            cwd=ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if result.returncode == 0:
            return str(candidate)
    raise RuntimeError("ultralytics is not importable. Install requirements-model.txt or run with project .venv.")


def require_tensorflow_for_tflite(python_bin: str) -> None:
    result = subprocess.run(
        [python_bin, "-c", "import tensorflow"],
        cwd=ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            "TFLite export requires TensorFlow in the export Python environment. "
            "The current project .venv uses Python 3.14, where TensorFlow wheels may be unavailable. "
            "Create a Python 3.12 export venv, install requirements-model.txt plus tensorflow<=2.19, "
            "then rerun this script with that interpreter active."
        )


def run_export(model: Path, imgsz: int, output_name: str, output_dir: Path, dry_run: bool, python_bin: str) -> Path:
    if not model.exists():
        raise FileNotFoundError(f"model not found: {model}")
    output_dir.mkdir(parents=True, exist_ok=True)
    model = model.resolve()
    export_code = (
        "from ultralytics import YOLO; "
        f"YOLO({str(model)!r}).export(format='tflite', imgsz={imgsz}, nms=True, int8=False)"
    )
    cmd = [python_bin, "-c", export_code]
    if dry_run:
        print("DRY-RUN:", " ".join(str(part) for part in cmd))
        return output_dir / output_name
    subprocess.run(cmd, cwd=ROOT, check=True)
    exported = find_exported_tflite(model, output_name)
    target = output_dir / output_name
    shutil.copy2(exported, target)
    print(f"copied {exported} -> {target}")
    return target


def find_exported_tflite(model: Path, output_name: str) -> Path:
    candidates = sorted(model.parent.glob(f"{model.stem}*_saved_model/*.tflite")) + sorted(model.parent.glob("*.tflite"))
    if not candidates:
        raise FileNotFoundError(f"TFLite export output not found near {model.parent}")

    if "float32" in output_name:
        precision_matches = [path for path in candidates if path.name.endswith("_float32.tflite")]
        if precision_matches:
            return max(precision_matches, key=lambda path: path.stat().st_mtime)
    if "float16" in output_name:
        precision_matches = [path for path in candidates if path.name.endswith("_float16.tflite")]
        if precision_matches:
            return max(precision_matches, key=lambda path: path.stat().st_mtime)

    return max(candidates, key=lambda path: path.stat().st_mtime)


def load_config() -> dict[str, object]:
    if not CONFIG_PATH.exists():
        raise FileNotFoundError(f"Android runtime config not found: {CONFIG_PATH}")
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    print(f"using config: {CONFIG_PATH}")
    print("version:", config.get("version"))
    return config


def model_export_spec(config: dict[str, object], model_key: str) -> tuple[int, str]:
    models = config.get("models")
    if not isinstance(models, dict) or model_key not in models:
        raise RuntimeError(f"model config missing: {model_key}")
    model = models[model_key]
    if not isinstance(model, dict):
        raise RuntimeError(f"invalid model config: {model_key}")
    input_size = int(model["input_size"])
    asset = str(model["asset"])
    asset_path = Path(asset)
    if asset_path.is_absolute() or ".." in asset_path.parts or asset_path.parent != Path("models"):
        raise RuntimeError(f"unsafe Android model asset path in config: {asset}")
    return input_size, asset_path.name


def main() -> None:
    parser = argparse.ArgumentParser(description="Export WalkSafe YOLO weights to Android TFLite assets.")
    parser.add_argument(
        "--unified",
        type=Path,
        default=None,
        help="Export one unified COCO+WalkSafe model using the unified_walksafe Android runtime spec.",
    )
    parser.add_argument("--custom", type=Path, default=DEFAULT_CUSTOM)
    parser.add_argument("--coco", type=Path, default=DEFAULT_COCO)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    config = load_config()
    custom_imgsz, custom_output_name = model_export_spec(config, "custom_tactile")
    coco_imgsz, coco_output_name = model_export_spec(config, "coco_general")

    python_bin = python_with_ultralytics()
    print("python:", python_bin)
    if not args.dry_run:
        require_tensorflow_for_tflite(python_bin)
    if args.unified is not None:
        unified_imgsz, unified_output_name = model_export_spec(config, "unified_walksafe")
        unified_target = run_export(
            model=args.unified,
            imgsz=unified_imgsz,
            output_name=unified_output_name,
            output_dir=args.output_dir,
            dry_run=args.dry_run,
            python_bin=python_bin,
        )
        print("unified:", unified_target)
        return

    if config.get("primary_model") == "unified_walksafe":
        print("NOTE: Android primary_model is unified_walksafe. Pass --unified <best.pt> to populate the primary asset; exporting custom+coco only updates the legacy fallback.")

    custom_target = run_export(
        model=args.custom,
        imgsz=custom_imgsz,
        output_name=custom_output_name,
        output_dir=args.output_dir,
        dry_run=args.dry_run,
        python_bin=python_bin,
    )
    coco_target = run_export(
        model=args.coco,
        imgsz=coco_imgsz,
        output_name=coco_output_name,
        output_dir=args.output_dir,
        dry_run=args.dry_run,
        python_bin=python_bin,
    )
    print("custom:", custom_target)
    print("coco:", coco_target)


if __name__ == "__main__":
    main()
