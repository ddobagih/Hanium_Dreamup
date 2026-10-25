# Current model boundary references

These three full raw tensors were produced by the current application TFLite model
on the host CPU. `manifest.json` binds the model, config, JPEGs, decoded RGB, exact
input tensor, raw output, thresholds and actual top-class boundary candidates.
Two existing declared boundary photos produce candidates within the unchanged
0.02 window; `coco_785` is the negative coverage control.

The JUnit test replays these real outputs through `RuntimeCalibrationOutputGate`
and checks that the production bilinear preprocessor reproduces the measured
input bits from the recorded RGB. It also checks current assets against the hashes.
RGB decoding used OpenCV, so Android Bitmap/YUV decoding, native Android CPU/GPU
execution and delegate output equivalence are not verified by this fixture. Class
scores are boundary evidence, not ground truth or accuracy measurements.

The photographs and their attribution remain in the existing runtime-calibration
assets; original source attribution is also preserved in each reference row.
Compressed RGB is decoded from those photographs without cropping. Raw tensors
contain all 25 × 12096 values, with no edited scores or coordinates.

`generate_reference.py` uses TensorFlow 2.19.1, OpenCV 4.13.0 and NumPy from the
existing local TFLite environment. It writes only this reference directory and
refuses changed model/config hashes. A changed model needs new measured selection
and review; do not widen the window or turn INCONCLUSIVE into success.
