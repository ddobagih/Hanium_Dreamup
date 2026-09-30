#!/usr/bin/env python3
"""Exercise the configured Android raw tensor contract, not recognition accuracy."""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.check_android_tflite_contract_20260531 import assert_raw_tensor_contract

DEFAULT_CONFIG = ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"


def load_interpreter(model_path: Path):
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        try:
            from tflite_runtime.interpreter import Interpreter
        except ImportError:
            try:
                from tensorflow.lite.python.interpreter import Interpreter
            except ImportError as error:
                raise RuntimeError("ai-edge-litert, tflite-runtime or TensorFlow is required; NOT_RUN") from error
    return Interpreter(model_path=str(model_path), num_threads=4)


def load_contract(config_path: Path) -> dict:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    if config.get("primary_model") != "unified_walksafe":
        raise ValueError("this smoke check requires the unified raw primary model")
    model = config.get("models", {}).get("unified_walksafe", {})
    if model.get("enabled", True) is not True:
        raise ValueError("configured primary model is disabled")
    errors: list[str] = []
    assert_raw_tensor_contract(errors, model)
    if errors:
        raise ValueError("; ".join(errors))
    return model


def letterbox_image(image_path: Path | None, input_size: int) -> np.ndarray:
    if image_path is None:
        return np.full((1, input_size, input_size, 3), 114.0 / 255.0, dtype=np.float32)
    with Image.open(image_path) as source:
        image = source.convert("RGB")
        scale = min(input_size / image.width, input_size / image.height)
        resized_size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        resized = image.resize(resized_size, Image.Resampling.BILINEAR)
    canvas = Image.new("RGB", (input_size, input_size), (114, 114, 114))
    canvas.paste(resized, ((input_size - resized.width) // 2, (input_size - resized.height) // 2))
    return np.asarray(canvas, dtype=np.float32)[None, ...] / 255.0


def validate_output(output: np.ndarray, model: dict) -> dict:
    expected = model["export"]["output_shape"]
    if list(output.shape) != expected or output.dtype != np.float32:
        raise ValueError(f"unexpected raw output: {output.shape} {output.dtype}; expected {expected} float32")
    if not np.isfinite(output).all():
        raise ValueError("model output contains NaN or infinity")
    boxes, scores = output[0, :4, :], output[0, 4:, :]
    if scores.shape[0] != len(model["classes"]):
        raise ValueError("raw score channels differ from class count")
    if np.any(scores < 0) or np.any(scores > 1):
        raise ValueError("raw class scores are outside [0, 1]")
    # Normalized centers can extend outside the image; dimensions cannot be negative.
    if np.any(boxes[2:, :] < 0):
        raise ValueError("raw box width or height is negative")
    maximum_scores = scores.max(axis=0)
    return {
        "finite_output": True,
        "anchor_count": int(scores.shape[1]),
        "class_count": int(scores.shape[0]),
        "anchors_above_0_001": int(np.count_nonzero(maximum_scores > 0.001)),
        "maximum_score": float(maximum_scores.max()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument("--model", type=Path, help="must match configured artifact SHA-256")
    parser.add_argument("--image", type=Path, help="optional image; otherwise synthetic gray input")
    args = parser.parse_args()
    model = load_contract(args.config)
    model_path = args.model or ROOT / "apps/android/app/src/main/assets" / model["asset"]
    digest = hashlib.sha256(model_path.read_bytes()).hexdigest()
    if digest != model.get("artifact_sha256"):
        raise ValueError("model file does not match configured artifact SHA-256")
    interpreter = load_interpreter(model_path.resolve())
    interpreter.allocate_tensors()
    inputs, outputs = interpreter.get_input_details(), interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise RuntimeError("expected exactly one input and one output tensor")
    input_detail, output_detail = inputs[0], outputs[0]
    for kind, detail in (("input", input_detail), ("output", output_detail)):
        expected = model["export"][f"{kind}_shape"]
        if detail["shape"].tolist() != expected or detail["dtype"] != np.float32:
            raise RuntimeError(f"unexpected {kind} contract: {detail['shape']} {detail['dtype']}")
    interpreter.set_tensor(input_detail["index"], letterbox_image(args.image, model["input_size"]))
    interpreter.invoke()
    result = validate_output(interpreter.get_tensor(output_detail["index"]), model)
    print(json.dumps({
        "ok": True, "check_scope": "tensor_inference_smoke_only",
        "input_kind": "image" if args.image else "synthetic_gray", "accuracy_evaluated": False,
        "model_sha256": digest, "input_shape": model["export"]["input_shape"],
        "output_shape": model["export"]["output_shape"], **result,
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
