# Separate 21-class detector regression set

The set contains one reviewed class-positive photo for each current class and
three real-photograph background crops: brick wall pattern, pavement shadow and
ordinary lane marking. It is separate from the small runtime-calibration fixture
and does not modify the shipped Android assets. Originals and historical dataset
annotations remain unchanged.

`manifest.json` preserves original photo/label paths and hashes, selected target
boxes, explicit crop coordinates, current model/config hashes, preprocessing and
all captured host CPU output hashes. Full raw outputs contain unedited float32
channels. The JUnit test uses the production parser, NMS and source-coordinate
mapping and logs target hits and real background false positives. Test PASS means
the set and replay checks ran, not that every photo was detected correctly.

Positive annotations describe only the selected target class and may omit other
objects. Background crops were visually checked; their containing original scenes
also contain positives outside the crop. Neither missing labels nor absence of a
selected target in the full scene is used to create negative truth. Scooter labels
are dataset categories and do not prove physical motion or abandonment.

This is a bounded local regression set, not a formal accuracy benchmark, walking
experiment, safety evaluation or Android CPU/GPU equivalence check. Host decoding
used OpenCV 4.13.0 and inference used TensorFlow 2.19.1 with CPU XNNPACK, threads2.
IoU 0.5 in the replay report is an evaluation matching convention, not a product
threshold. Misses and false positives are retained in the report.

`verify_native_captures.py` can replay the packaged photos in the recorded host
environment, check every decoded RGB/input/native output hash and leave all files
unchanged. A model/config/environment change requires new capture and review;
recorded outputs are not substituted for running a changed model.
