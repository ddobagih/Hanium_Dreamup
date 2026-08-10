#!/usr/bin/env python3
"""Invoke the Android 768 TFLite asset and verify its end-to-end tensor contract."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = (
    ROOT
    / "apps/android/app/src/main/assets/models"
    / "walksafe_unified_yolo26n_768_float32.tflite"
)
INPUT_SIZE = 768
OUTPUT_ROWS = 300
OUTPUT_COLUMNS = 6
CLASS_COUNT = 13


def load_interpreter(model_path: Path):
    try:
        from tflite_runtime.interpreter import Interpreter
    except ImportError:
        try:
            from tensorflow.lite import Interpreter
        except ImportError as error:
            raise RuntimeError("tflite-runtime or TensorFlow is required") from error
    return Interpreter(model_path=str(model_path), num_threads=4)


def letterbox_image(image_path: Path) -> np.ndarray:
    with Image.open(image_path) as source:
        image = source.convert("RGB")
        scale = min(INPUT_SIZE / image.width, INPUT_SIZE / image.height)
        resized_size = (
            max(1, round(image.width * scale)),
            max(1, round(image.height * scale)),
        )
        resized = image.resize(resized_size, Image.Resampling.BILINEAR)
    canvas = Image.new("RGB", (INPUT_SIZE, INPUT_SIZE), (114, 114, 114))
    canvas.paste(
        resized,
        ((INPUT_SIZE - resized.width) // 2, (INPUT_SIZE - resized.height) // 2),
    )
    return np.asarray(canvas, dtype=np.float32)[None, ...] / 255.0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--image", type=Path, required=True)
    args = parser.parse_args()

    interpreter = load_interpreter(args.model.resolve())
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1 or len(outputs) != 1:
        raise RuntimeError("expected exactly one input and one output tensor")
    input_detail = inputs[0]
    output_detail = outputs[0]
    expected_input = [1, INPUT_SIZE, INPUT_SIZE, 3]
    expected_output = [1, OUTPUT_ROWS, OUTPUT_COLUMNS]
    if input_detail["shape"].tolist() != expected_input or input_detail["dtype"] != np.float32:
        raise RuntimeError(f"unexpected input contract: {input_detail['shape']} {input_detail['dtype']}")
    if output_detail["shape"].tolist() != expected_output or output_detail["dtype"] != np.float32:
        raise RuntimeError(f"unexpected output contract: {output_detail['shape']} {output_detail['dtype']}")

    interpreter.set_tensor(input_detail["index"], letterbox_image(args.image.resolve()))
    interpreter.invoke()
    output = interpreter.get_tensor(output_detail["index"])
    if not np.isfinite(output).all():
        raise RuntimeError("model output contains NaN or infinity")

    rows = output[0]
    active = rows[rows[:, 4] > 0.001]
    if active.size:
        scores = active[:, 4]
        class_ids = active[:, 5]
        if np.any(scores < 0) or np.any(scores > 1):
            raise RuntimeError("detection score is outside [0, 1]")
        if np.any(np.abs(class_ids - np.round(class_ids)) > 1e-4):
            raise RuntimeError("class id is not integer-valued")
        if np.any(class_ids < 0) or np.any(class_ids >= CLASS_COUNT):
            raise RuntimeError("class id is outside the configured 13-class range")

    print(
        json.dumps(
            {
                "ok": True,
                "input_shape": expected_input,
                "output_shape": expected_output,
                "finite_output": True,
                "active_rows": int(active.shape[0]),
                "maximum_score": float(active[:, 4].max()) if active.size else 0.0,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
