# 2026-05-15 Model ONNX follow-up

Scope: v2 `best.pt` 확인, `.venv` export 의존성 확인, ONNX export, 빠른 동등성/latency smoke. 학습은 실행하지 않았다. 데이터셋과 기존 run은 삭제/이동하지 않았다.

## Initial repo/disk state

Command: `git status --short`

```text
 M daylog/2026-05-15.md
?? docs/execution/2026-05-15_backend_validation.md
?? docs/execution/2026-05-15_disk_cleanup_candidates.md
?? docs/execution/2026-05-15_installed_program_usage_candidates.md
?? docs/execution/2026-05-15_low_risk_cleanup_result.md
?? docs/execution/2026-05-15_model_validation.md
?? docs/execution/2026-05-15_obs_removal_attempt.md
?? docs/execution/2026-05-15_parallel_validation_summary.md
?? docs/execution/2026-05-15_pwa_validation.md
?? docs/execution/2026-05-15_voice_validation.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_attempt.md
?? docs/execution/2026-05-15_wine_obs_snap_cleanup_result.md
```

Command: `df -h .`

```text
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme0n1p2  915G  851G   18G  99% /
```

## v2 best.pt confirmation

| item | value |
|---|---|
| path | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt` |
| size | `5,447,386 bytes` |
| mtime | `2026-05-12 16:27:57.324744856 +0900` |
| sha256 | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |

Freeze manifest check passed:

```text
runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt: OK
runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt: OK
runs/detect/walksafe_kr_tactile_v2_full/results.csv: OK
runs/detect/walksafe_kr_tactile_v2_full/args.yaml: OK
datasets/walksafe_kr_v2/data.yaml: OK
```

## `.venv` export dependencies

No package install/download was performed.

```text
python 3.14.4 (main, Apr  8 2026, 04:02:31) [GCC 15.2.0]
ultralytics: OK 8.4.48
torch: OK 2.11.0+cu130
onnx: OK 1.21.0
onnxruntime: OK 1.26.0
numpy: OK 2.4.4
cv2: OK 4.13.0
```

## ONNX export

Command:

```bash
/usr/bin/time -f 'elapsed=%E max_rss_kb=%M' \
  .venv/bin/yolo export \
  model=runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  format=onnx imgsz=640 opset=18 simplify=False dynamic=False batch=1
```

Result: PASS.

```text
ONNX: export success, saved as 'runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx' (10.1 MB)
elapsed=0:03.12 max_rss_kb=891796
```

Artifact:

| item | value |
|---|---|
| path | `runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx` |
| size | `10,567,352 bytes` |
| mtime | `2026-05-15 11:52:05.772424361 +0900` |
| sha256 | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |
| git ignore | ignored by `.gitignore:15:runs/` |

Post-export disk state stayed effectively unchanged at the displayed precision:

```text
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme0n1p2  915G  851G   18G  99% /
```

## Equivalence status

Status: PASS for fast raw tensor smoke; full test-split metric equivalence was not run in this lane.

Checks performed:

- `onnx.checker.check_model`: PASS
- same deterministic `(1, 3, 640, 640)` float32 tensor through fused PT model and ONNX Runtime CPU

```text
raw_equivalence_shape_pt: (1, 8, 8400)
raw_equivalence_shape_onnx: (1, 8, 8400)
raw_equivalence_max_abs: 0.0010375977
raw_equivalence_mean_abs: 0.0000131680
raw_equivalence_allclose_rtol1e-03_atol1e-03: True
```

## Latency status

Status: quick CPU smoke only, not a full benchmark. Measurement used 30 test images from `datasets/walksafe_kr_v2/images/test`, `imgsz=640`, `device=cpu`, Ultralytics preprocessing/postprocessing included, image disk read excluded.

| model | mean ms | p50 ms | p95 ms | min ms | max ms |
|---|---:|---:|---:|---:|---:|
| PT | 9.82 | 8.32 | 13.70 | 7.93 | 19.83 |
| ONNX Runtime CPU | 47.66 | 47.47 | 68.67 | 26.94 | 72.09 |

`latency_onnx_over_pt_p95_ratio: 5.014`.

Interpretation: ONNX CPU p95 is under `250ms` in this local smoke, so it remains a backend/server candidate. It is not within the stricter `1.2x` PT p95 threshold, and browser/ONNX Runtime Web latency was not tested.

## Git artifact safety

Tracked model/run artifact scan still only reports existing dataset `.gitkeep` placeholders; `.pt`, `.onnx`, `runs/**`, and `datasets/walksafe_kr_v2/**` artifacts are not tracked.
