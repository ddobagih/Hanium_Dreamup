# Stage1 detect.v2 image smoke

```json
{
  "created_at": "2026-05-25T15:33:49.090654Z",
  "custom_only": {
    "reason": "current detect_v2 real provider requires configured custom_tactile and coco_general model paths",
    "supported": false
  },
  "defaults": {
    "coco_model": "yolo26n.pt",
    "custom_model": "runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt",
    "image_dir": "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test",
    "runtime_config": "configs/walksafe_two_model_runtime_stage1_mvp_20260523.json"
  },
  "expectations": {
    "expect_target_detected": false,
    "minimum_target_detected_images": null,
    "target_class": "damaged_tactile_block"
  },
  "health": {
    "coco_model_path": "yolo26n.pt",
    "custom_tactile_model_path": "runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt",
    "mode": "real",
    "reason": null,
    "runtime_config_path": "configs/walksafe_two_model_runtime_stage1_mvp_20260523.json",
    "schema_version": "detect.v2",
    "status": "ready"
  },
  "images": [
    {
      "detection_count": 0,
      "detections": [],
      "http_status": 200,
      "image": "/tmp/hanium_phone_smoke/walksafe_after_camera.png",
      "latency_ms": 1436.12
    }
  ],
  "inputs": {
    "coco_model": {
      "exists": true,
      "path": "yolo26n.pt"
    },
    "custom_model": {
      "exists": true,
      "path": "runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt"
    },
    "image": {
      "exists": true,
      "path": "/tmp/hanium_phone_smoke/walksafe_after_camera.png"
    },
    "image_dir": {
      "exists": false,
      "path": "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test"
    },
    "input_mode": "single_image",
    "runtime_config": {
      "exists": true,
      "path": "configs/walksafe_two_model_runtime_stage1_mvp_20260523.json"
    }
  },
  "next_command": null,
  "reason": null,
  "result_summary": {
    "class_detection_counts": {},
    "latency_ms": {
      "avg": 1436.12,
      "count": 1,
      "max": 1436.12,
      "min": 1436.12
    },
    "target_class": "damaged_tactile_block",
    "target_detected_image_count": 0,
    "target_detected_images": []
  },
  "schema_version": "stage1_detect_v2_image_smoke.v3",
  "scope": {
    "asgi": true,
    "max_images": 5,
    "phone_required": false,
    "route": "/detect/v2"
  },
  "skipped_images": [],
  "status": "passed",
  "target_selection": {
    "mode": "single_image",
    "selected_count": 1,
    "selected_image_size_bytes": 1859534,
    "selected_images": [
      "/tmp/hanium_phone_smoke/walksafe_after_camera.png"
    ],
    "selected_target_positive_count": null,
    "selected_target_positive_images": [],
    "target_positive_eligible_count": null
  },
  "usage": {
    "blocked_exit_zero": "add --blocked-exit-zero only when documenting a local BLOCKED state should not fail the caller",
    "image_dir": ".venv/bin/python scripts/check_detect_v2_stage1_image_smoke_20260524.py --image-dir <dataset_images_test_dir> --output docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md",
    "image_dir_expect_target": ".venv/bin/python scripts/check_detect_v2_stage1_image_smoke_20260524.py --image-dir <dataset_images_test_dir> --output docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md --target-positive only --expect-target-detected",
    "single_image": ".venv/bin/python scripts/check_detect_v2_stage1_image_smoke_20260524.py --image /tmp/hanium_phone_smoke/walksafe_after_camera.png --output docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md"
  }
}
```
