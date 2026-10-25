"""Read-only verification of this bounded set against its recorded host environment."""
from pathlib import Path
import os

os.environ.update(
    CUDA_VISIBLE_DEVICES="-1", TF_CPP_MIN_LOG_LEVEL="3",
    TF_NUM_INTRAOP_THREADS="2", TF_NUM_INTEROP_THREADS="1",
    OMP_NUM_THREADS="2", OPENBLAS_NUM_THREADS="2",
)

import hashlib
import json
import cv2
import numpy as np
import tensorflow as tf

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parents[4] / "src/main/assets"
record = json.loads((HERE / "manifest.json").read_bytes())
sha = lambda data: hashlib.sha256(data).hexdigest()
config_bytes = (ASSETS / "model-config/two_model_runtime.json").read_bytes()
assert sha(config_bytes) == record["config_sha256"]
config = json.loads(config_bytes)["models"]["unified_walksafe"]
model = ASSETS / config["asset"]
assert sha(model.read_bytes()) == record["model_sha256"] == config["artifact_sha256"]
assert tf.__version__ == record["runtime"]["tensorflow"]
assert cv2.__version__ == record["runtime"]["opencv"]
interpreter = tf.lite.Interpreter(model_path=str(model), num_threads=2)
interpreter.allocate_tensors()
inp, op = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
assert inp["shape"].tolist() == record["input_shape"]
assert op["shape"].tolist() == record["output_shape"]
assert inp["dtype"] == op["dtype"] == np.float32

for row in record["rows"]:
    photo = (HERE / row["photo"]).read_bytes()
    assert sha(photo) == row["photo_sha256"]
    rgb = cv2.cvtColor(cv2.imdecode(np.frombuffer(photo, np.uint8), cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB)
    height, width = rgb.shape[:2]
    assert [width, height] == [row["width"], row["height"]]
    assert sha(rgb.tobytes()) == row["decoded_rgb_sha256"]
    size = config["input_size"]
    gain = min(size / width, size / height)
    rw, rh = round(width * gain), round(height * gain)
    left, top = round((size - rw) / 2 - .1), round((size - rh) / 2 - .1)
    pixels = np.full((size, size, 3), 114, np.uint8)
    pixels[top:top + rh, left:left + rw] = cv2.resize(rgb, (rw, rh), interpolation=cv2.INTER_LINEAR)
    tensor = (pixels.astype(np.float32)[None] / np.float32(255)).astype("<f4")
    assert sha(tensor.tobytes()) == row["input_sha256"]
    interpreter.set_tensor(inp["index"], tensor)
    interpreter.invoke()
    raw = interpreter.get_tensor(op["index"]).astype("<f4")
    assert np.isfinite(raw).all()
    assert sha(raw.tobytes()) == row["raw_sha256"], row["id"]
    print(row["id"], "native input/output hashes match", flush=True)
