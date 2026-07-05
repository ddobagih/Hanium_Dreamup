# AIHub189 depthprediction original full offline validation

- Date: 2026-07-02 KST
- Input: `/home/ddobagi/Downloads/한이음 드림업 데이터셋/인도보행 영상/뎁스프리딕션/Depth_001~005.zip`
- Input sha256: `f02358a0e1e784671d8de01aea94048b457a948bedf6b45596c01b24f03dea03`
- Output directory: `artifacts/aihub189_depthprediction_original_full_20260702/`
- Commit policy: original outer zip, inner zips, extracted images/depth, and generated artifact CSV/JSON outputs are not committed.

## Command

```bash
.venv/bin/python scripts/evaluate_aihub189_depthprediction_offline.py \
  --outer-zip '/home/ddobagi/Downloads/한이음 드림업 데이터셋/인도보행 영상/뎁스프리딕션/Depth_001~005.zip' \
  --output-dir artifacts/aihub189_depthprediction_original_full_20260702 \
  --full-original-zip-run
```

## Result

```json
{
  "source_kind": "offline_zed_reference",
  "reference_not_ground_truth": true,
  "arcore_pass": false,
  "frames_total": 2461,
  "frames_with_all_files": 2461,
  "detections_total": 2461,
  "evaluated": 2461,
  "skipped_missing_frame": 0,
  "skipped_missing_assets": 0,
  "skipped_low_quality": 0,
  "bucket_counts": {
    "ignore": 926,
    "stop": 883,
    "warning": 652
  },
  "scale_status": "unverified",
  "disp16_scale": 16.0,
  "confidence_policy": "disabled_unknown_direction",
  "detector_input_kind": "generated_probe_bbox",
  "full_original_zip_run": true,
  "frames_selected": 2461,
  "generated_probe_detections": 2461
}
```

## Interpretation

- This is an offline ZED disparity reference validation, not Android ARCore validation.
- `detector_input_kind=generated_probe_bbox` means the evaluator used generated frame-id probe boxes because no detector CSV was supplied. Treat bucket counts as depth pipeline/probe evidence, not detector performance.
- `scale_status=unverified` remains because `disp16_scale=16.0` was used from the current validation document assumption, not from a finalized dataset authority.
- `confidence_policy=disabled_unknown_direction` remains because confidence direction is not yet confirmed.
- `arcore_pass=false` is intentionally fixed for this evidence.
