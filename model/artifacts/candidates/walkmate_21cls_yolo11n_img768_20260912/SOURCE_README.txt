WalkMate 21-Class YOLO Model
================================

Recommended inference weight: model/best.pt
Resume-training checkpoint:   model/last.pt
Input image size used:         768 px
Architecture:                  YOLO11n
Number of classes:             21

Python example:
    from ultralytics import YOLO
    model = YOLO('model/best.pt')
    results = model.predict(source='image.jpg', imgsz=768, conf=0.25)

Contents:
    model/best.pt          recommended inference model
    model/last.pt          final resume checkpoint
    training/results.csv   per-epoch training/validation metrics
    training/state.json    final test metrics and run settings
    training/data.yaml     class IDs and dataset configuration
    training/inventory.csv per-class usable-image inventory

Note: data.yaml contains paths from the training computer. Those paths are not
required for inference. Change them before retraining on another computer.
