"""Regenerate only these host-CPU test references; never writes Android assets."""
from pathlib import Path
import os

os.environ.update(
    CUDA_VISIBLE_DEVICES="-1", TF_CPP_MIN_LOG_LEVEL="3",
    TF_NUM_INTRAOP_THREADS="2", TF_NUM_INTEROP_THREADS="1",
    OMP_NUM_THREADS="2", OPENBLAS_NUM_THREADS="2",
)

import gzip
import hashlib
import json
import cv2
import numpy as np
import tensorflow as tf

OUT = Path(__file__).resolve().parent
APP = OUT.parents[4]
ASSETS = APP / "src/main/assets"
MODEL_SHA = "d10aa174a2ceb8ae116a09b7b570e27ff71c296fbd98f240c11e69dbdb434b57"
CONFIG_SHA = "2d75fe233d7a321347575673172495635045dcad88b54f3c3f6e2d6ce166489e"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def store(name, data):
    (OUT / name).write_bytes(gzip.compress(data, mtime=0))
    return name


config_bytes = (ASSETS / "model-config/two_model_runtime.json").read_bytes()
assert sha(config_bytes) == CONFIG_SHA, "Review and reselect references for a changed config"
config = json.loads(config_bytes)["models"]["unified_walksafe"]
model = ASSETS / config["asset"]
assert sha(model.read_bytes()) == MODEL_SHA == config["artifact_sha256"]
original = json.loads((ASSETS / "runtime-calibration/manifest.json").read_bytes())
interpreter = tf.lite.Interpreter(model_path=str(model), num_threads=2)
interpreter.allocate_tensors()
inp, op = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
assert inp["shape"].tolist() == [1, 768, 768, 3]
assert op["shape"].tolist() == [1, 25, 12096]
assert inp["dtype"] == op["dtype"] == np.float32

rows = []
for fixture in original["fixtures"]:
    if fixture["kind"] != "JPEG":
        continue
    source = ASSETS / "runtime-calibration" / fixture["asset"]
    jpeg = source.read_bytes()
    assert sha(jpeg) == fixture["sha256"]
    rgb = cv2.cvtColor(cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB)
    height, width = rgb.shape[:2]
    assert [width, height] == [fixture["width"], fixture["height"]]
    gain = min(768 / width, 768 / height)
    rw, rh = round(width * gain), round(height * gain)
    left, top = round((768 - rw) / 2 - .1), round((768 - rh) / 2 - .1)
    pixels = np.full((768, 768, 3), 114, np.uint8)
    pixels[top:top + rh, left:left + rw] = cv2.resize(rgb, (rw, rh), interpolation=cv2.INTER_LINEAR)
    tensor = (pixels.astype(np.float32)[None] / np.float32(255)).astype("<f4")
    interpreter.set_tensor(inp["index"], tensor)
    interpreter.invoke()
    raw = interpreter.get_tensor(op["index"]).astype("<f4")
    assert np.isfinite(raw).all()
    values, anchors = raw[0], raw.shape[2]
    scores = values[4:]
    ids = scores.argmax(axis=0)
    best = scores[ids, np.arange(anchors)]
    thresholds = np.asarray([
        config["thresholds"].get(config["classes"][int(c)], config["thresholds"]["default"])
        for c in ids
    ], np.float32)
    allowed = np.asarray([config["classes"][int(c)] in config["allowlist"] for c in ids])
    cx, cy, w, h = values[:4]
    x, y = cx - w / np.float32(2), cy - h / np.float32(2)
    valid = ((best > 0) & (scores >= 0).all(axis=0) & (scores <= 1).all(axis=0)
             & (w > 0) & (h > 0) & (x < 1) & (y < 1) & (x + w > 0) & (y + h > 0) & allowed)
    near = np.flatnonzero(valid & (np.abs(best - thresholds) <= np.float32(.02)))
    examples = [dict(
        anchor=a, class_id=int(ids[a]), class_name=config["classes"][int(ids[a])],
        score=float(best[a]), threshold=float(thresholds[a]),
        box_xywh_normalized=[float(x[a]), float(y[a]), float(w[a]), float(h[a])],
    ) for a in sorted(near.tolist(), key=lambda a:float(abs(best[a] - thresholds[a])))]
    fixture_id = fixture["id"]
    rows.append(dict(
        fixture_id=fixture_id, source_asset="runtime-calibration/" + fixture["asset"],
        source_sha256=sha(jpeg), source_dimensions=[width, height], source=fixture["source"],
        rgb_file=store(fixture_id + ".rgb.gz", rgb.tobytes()), rgb_sha256=sha(rgb.tobytes()),
        input_sha256=sha(tensor.tobytes()),
        raw_file=store(fixture_id + ".raw-f32le.gz", raw.tobytes()), raw_sha256=sha(raw.tobytes()),
        near_count=int(len(near)), examples=examples,
    ))

assert {r["fixture_id"] for r in rows if r["near_count"]} == {"coco_885", "coco_11149"}
record = dict(
    schema_version=1, model_sha256=MODEL_SHA, config_sha256=CONFIG_SHA,
    source_model_sha256=config["source_model_sha256"],
    input_shape=[1,768,768,3], output_shape=[1,25,12096], tensor_encoding="FLOAT32_LITTLE_ENDIAN",
    runtime=dict(tensorflow=tf.__version__, opencv=cv2.__version__, delegate="host_cpu_default_XNNPACK", threads=2),
    preprocessing="OpenCV JPEG decode to RGB uint8, INTER_LINEAR, round-to-even resize and integer pad114, float32 /255",
    near_threshold_window=.02, android_runtime_verified=False, rows=rows,
    scope="Measured host-model boundary selection and production-code replay; not Android delegate equivalence or accuracy evaluation",
)
(OUT / "manifest.json").write_text(json.dumps(record, indent=2) + "\n")
for row in rows:
    print(row["fixture_id"], "near_count", row["near_count"], "raw_sha256", row["raw_sha256"])
