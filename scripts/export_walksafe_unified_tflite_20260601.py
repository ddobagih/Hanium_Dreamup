#!/usr/bin/env python3
"""Export a unified WalkSafe YOLO model to TFLite.

This is intentionally separate from the legacy two-model Android exporter.  It
exports one trained model that already contains the COCO general-object classes
plus tactile classes.  No model training or download is performed here.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = ROOT / "runs/detect/walksafe_unified_yolo26n_640_13cls_20260602/weights/best.pt"
DEFAULT_DATA = ROOT / "datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml"
DEFAULT_OUTPUT_DIR = ROOT / "exports/android-unified"
DEFAULT_OUTPUT_NAME = "walksafe_unified_yolo26n_640_float32.tflite"
ANDROID_ASSET = ROOT / "apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_640_float32.tflite"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export unified WalkSafe YOLO model to TFLite.")
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--data", type=Path, default=DEFAULT_DATA, help="Optional data.yaml used to write class metadata sidecar.")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--output-name", default=DEFAULT_OUTPUT_NAME)
    parser.add_argument("--python", dest="python_bin", default=None, help="Python interpreter with ultralytics/tensorflow installed.")
    parser.add_argument("--no-nms", action="store_true", help="Disable Ultralytics TFLite NMS export. Android parser expects NMS output by default.")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def python_with_ultralytics(explicit_python: str | None) -> str:
    candidates = [Path(explicit_python)] if explicit_python else [Path(sys.executable), ROOT / ".venv/bin/python"]
    for candidate in candidates:
        if not candidate or not candidate.exists():
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
    raise RuntimeError("ultralytics is not importable. Install requirements-model.txt or pass --python.")


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
            "Use a Python 3.12 export venv with requirements-model.txt plus tensorflow<=2.19."
        )


def parse_data_yaml_names(path: Path) -> dict[int, str]:
    if not path.exists():
        return {}
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        names = data.get("names", {})
        if isinstance(names, list):
            return {idx: str(name) for idx, name in enumerate(names)}
        if isinstance(names, dict):
            return {int(class_id): str(name) for class_id, name in names.items()}
    except Exception:
        pass

    names: dict[int, str] = {}
    in_names = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].rstrip()
        if not line:
            continue
        if line.startswith("names:"):
            in_names = True
            continue
        if in_names and raw_line.startswith((" ", "\t")) and ":" in line:
            key, value = line.strip().split(":", 1)
            try:
                names[int(key.strip())] = value.strip().strip("'\"")
            except ValueError:
                continue
        elif in_names:
            break
    return names


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


def write_sidecar(target: Path, args: argparse.Namespace, class_names: dict[int, str]) -> Path:
    sidecar = target.with_suffix(".json")
    payload: dict[str, Any] = {
        "model_key": "unified_walksafe",
        "asset_name": target.name,
        "source_weights": str(args.model),
        "data_yaml": str(args.data),
        "input_size": args.imgsz,
        "export_format": "tflite",
        "nms": not args.no_nms,
        "output_contract": "[1,300,6] rows: x1,y1,x2,y2,score,class_id when exported with nms=True",
        "classes": [class_names[class_id] for class_id in sorted(class_names)],
    }
    sidecar.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return sidecar


def main() -> int:
    args = parse_args()
    model = args.model.expanduser()
    output_dir = args.output_dir.expanduser()
    target = output_dir / args.output_name
    class_names = parse_data_yaml_names(args.data.expanduser())

    print("Unified TFLite export plan:")
    print(f"  model: {model}")
    print(f"  data: {args.data}")
    print(f"  imgsz: {args.imgsz}")
    print(f"  nms: {not args.no_nms}")
    print(f"  output: {target}")
    print(f"  android asset target: {ANDROID_ASSET}")
    if class_names:
        print("  classes:")
        for class_id, class_name in sorted(class_names.items()):
            print(f"    {class_id}: {class_name}")

    if args.dry_run:
        python_bin = args.python_bin or sys.executable
    else:
        python_bin = python_with_ultralytics(args.python_bin)
    print(f"  python: {python_bin}")
    export_code = (
        "from ultralytics import YOLO; "
        f"YOLO({str(model.resolve())!r}).export(format='tflite', imgsz={args.imgsz}, nms={not args.no_nms}, int8=False)"
    )
    cmd = [python_bin, "-c", export_code]
    if args.dry_run:
        print("DRY-RUN:", " ".join(str(part) for part in cmd))
        print("DRY-RUN: would copy exported TFLite and write sidecar metadata.")
        print("DRY-RUN: to populate Android directly, use scripts/export_android_tflite_models_20260531.py --unified <best.pt>")
        return 0

    if not model.exists():
        raise FileNotFoundError(f"model not found: {model}")
    require_tensorflow_for_tflite(python_bin)
    output_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(cmd, cwd=ROOT, check=True)
    exported = find_exported_tflite(model.resolve(), args.output_name)
    shutil.copy2(exported, target)
    sidecar = write_sidecar(target, args, class_names)
    print(f"copied {exported} -> {target}")
    print(f"wrote {sidecar}")
    print(f"Android drop-in target: {ANDROID_ASSET}")
    print("Or export directly with: python scripts/export_android_tflite_models_20260531.py --unified <best.pt>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
