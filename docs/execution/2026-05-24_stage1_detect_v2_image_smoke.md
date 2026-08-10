# Stage1 detect.v2 image smoke

```json
{
  "created_at": "2026-05-24T15:14:31.496146Z",
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
  "health": {
    "coco_model_path": "/home/ddobagi/Code/hanium-dreamup/yolo26n.pt",
    "custom_tactile_model_path": "/home/ddobagi/Code/hanium-dreamup/runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt",
    "mode": "real",
    "reason": null,
    "runtime_config_path": "/home/ddobagi/Code/hanium-dreamup/configs/walksafe_two_model_runtime_stage1_mvp_20260523.json",
    "schema_version": "detect.v2",
    "status": "ready"
  },
  "images": [
    {
      "detection_count": 2,
      "detections": [
        {
          "bbox": {
            "height": 0.9989498853683472,
            "width": 0.37752604484558105,
            "x": 0.31934088468551636,
            "y": 0.0
          },
          "captured_at": "2026-05-24T15:14:32.341694Z",
          "category": "tactile",
          "class_name": "damaged_tactile_block",
          "confidence": 0.9762377738952637,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 1,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.5
        },
        {
          "bbox": {
            "height": 0.10108682513237,
            "width": 0.18653225898742676,
            "x": 0.40604841709136963,
            "y": 0.45548972487449646
          },
          "captured_at": "2026-05-24T15:14:32.341694Z",
          "category": "tactile",
          "class_name": "tactile_damage_area",
          "confidence": 0.9305455088615417,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 2,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.8
        }
      ],
      "http_status": 200,
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210719_0000006121.jpg",
      "latency_ms": 4267.43
    },
    {
      "detection_count": 1,
      "detections": [
        {
          "bbox": {
            "height": 0.9883812665939331,
            "width": 0.3687065839767456,
            "x": 0.3048163056373596,
            "y": 0.0
          },
          "captured_at": "2026-05-24T15:14:36.609303Z",
          "category": "tactile",
          "class_name": "damaged_tactile_block",
          "confidence": 0.9498778581619263,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 1,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.5
        }
      ],
      "http_status": 200,
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000037036.jpg",
      "latency_ms": 276.32
    },
    {
      "detection_count": 2,
      "detections": [
        {
          "bbox": {
            "height": 0.9947600404411787,
            "width": 0.47230884432792664,
            "x": 0.22293546795845032,
            "y": 0.00018781026301439852
          },
          "captured_at": "2026-05-24T15:14:36.885742Z",
          "category": "tactile",
          "class_name": "damaged_tactile_block",
          "confidence": 0.9667043685913086,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 1,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.5
        },
        {
          "bbox": {
            "height": 0.06989657878875732,
            "width": 0.26230373978614807,
            "x": 0.2838766872882843,
            "y": 0.5501471757888794
          },
          "captured_at": "2026-05-24T15:14:36.885742Z",
          "category": "tactile",
          "class_name": "tactile_damage_area",
          "confidence": 0.8558163046836853,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 2,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.8
        }
      ],
      "http_status": 200,
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000061307.jpg",
      "latency_ms": 256.32
    },
    {
      "detection_count": 1,
      "detections": [
        {
          "bbox": {
            "height": 0.9919303298229352,
            "width": 0.4037269949913025,
            "x": 0.2500225901603699,
            "y": 0.0006357192760333419
          },
          "captured_at": "2026-05-24T15:14:37.142249Z",
          "category": "tactile",
          "class_name": "damaged_tactile_block",
          "confidence": 0.9648377299308777,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 1,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.5
        }
      ],
      "http_status": 200,
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000061452.jpg",
      "latency_ms": 244.39
    },
    {
      "detection_count": 1,
      "detections": [
        {
          "bbox": {
            "height": 0.9948678016662598,
            "width": 0.38512569665908813,
            "x": 0.3151838779449463,
            "y": 0.0
          },
          "captured_at": "2026-05-24T15:14:37.386756Z",
          "category": "tactile",
          "class_name": "damaged_tactile_block",
          "confidence": 0.6527197360992432,
          "gps": {
            "accuracy_m": 10.0,
            "latitude": 37.5665,
            "longitude": 126.978
          },
          "heading": 180.0,
          "model_class_id": 1,
          "model_key": "custom_tactile",
          "schema_version": "detect.v2",
          "source_model": "walksafe_tactile3_reviewed_yolo26s_stage1_best_20260523",
          "threshold_used": 0.5
        }
      ],
      "http_status": 200,
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000174789.jpg",
      "latency_ms": 233.06
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
    "image_dir": "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test",
    "runtime_config": {
      "exists": true,
      "path": "configs/walksafe_two_model_runtime_stage1_mvp_20260523.json"
    }
  },
  "reason": null,
  "result_summary": {
    "class_detection_counts": {
      "damaged_tactile_block": 5,
      "tactile_damage_area": 2
    },
    "latency_ms": {
      "avg": 1055.5,
      "count": 5,
      "max": 4267.43,
      "min": 233.06
    },
    "target_class": "damaged_tactile_block",
    "target_detected_image_count": 5,
    "target_detected_images": [
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210719_0000006121.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000037036.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000061307.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000061452.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000174789.jpg"
    ]
  },
  "schema_version": "stage1_detect_v2_image_smoke.v1",
  "scope": {
    "asgi": true,
    "max_images": 5,
    "phone_required": false,
    "route": "/detect/v2"
  },
  "skipped_images": [
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210721_0000038815.jpg",
      "reason": "upload_too_large",
      "size_bytes": 8839431
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210723_0000012034.jpg",
      "reason": "upload_too_large",
      "size_bytes": 10898011
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210724_0000002442.jpg",
      "reason": "upload_too_large",
      "size_bytes": 10112226
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210724_0000038847.jpg",
      "reason": "upload_too_large",
      "size_bytes": 9938914
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210726_0000099025.jpg",
      "reason": "upload_too_large",
      "size_bytes": 8459986
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210727_0000089549.jpg",
      "reason": "upload_too_large",
      "size_bytes": 8541017
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210728_0000017544.jpg",
      "reason": "upload_too_large",
      "size_bytes": 13220232
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210729_0000018340.jpg",
      "reason": "upload_too_large",
      "size_bytes": 8499180
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210729_0000025103.jpg",
      "reason": "upload_too_large",
      "size_bytes": 11288643
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210729_0000068391.jpg",
      "reason": "upload_too_large",
      "size_bytes": 11316193
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210730_0000091632.jpg",
      "reason": "upload_too_large",
      "size_bytes": 8605495
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210806_0000106398.jpg",
      "reason": "upload_too_large",
      "size_bytes": 9752935
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210806_0000107314.jpg",
      "reason": "upload_too_large",
      "size_bytes": 10485764
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210806_0000109921.jpg",
      "reason": "upload_too_large",
      "size_bytes": 10775752
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210806_0000109987.jpg",
      "reason": "upload_too_large",
      "size_bytes": 11437279
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210806_0000110058.jpg",
      "reason": "upload_too_large",
      "size_bytes": 10052555
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210807_0000121675.jpg",
      "reason": "upload_too_large",
      "size_bytes": 11145725
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210808_0000121723.jpg",
      "reason": "upload_too_large",
      "size_bytes": 11801892
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210808_0000121726.jpg",
      "reason": "upload_too_large",
      "size_bytes": 9095537
    },
    {
      "image": "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_0_1_1_1_20210809_0000206089.jpeg",
      "reason": "upload_too_large",
      "size_bytes": 8641658
    }
  ],
  "status": "passed",
  "target_selection": {
    "class_names": {
      "0": "normal_tactile_block",
      "1": "damaged_tactile_block",
      "2": "tactile_damage_area"
    },
    "eligible_count": 816,
    "label_dir": "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/labels/test",
    "mode": "prefer",
    "selected_count": 5,
    "selected_target_positive_count": 5,
    "selected_target_positive_images": [
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210719_0000006121.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000037036.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000061307.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000061452.jpg",
      "datasets/walksafe_kr_v2/images/test/aihub513_tactile_2_09_1_1_1_1_20210720_0000174789.jpg"
    ],
    "skipped_count": 1531,
    "skipped_detail_limit": 20,
    "target_class": "damaged_tactile_block",
    "target_class_index": 1,
    "target_positive_eligible_count": 491
  }
}
```
