# Task prompt for a GitHub AI/Codex agent

You are working inside `ai_tasks/walksafe_v3_relabel_20260521`.

## Objective

Create corrected YOLO labels for the 20 images in `images/`.

## Inputs

- Full images: `images/*.jpg`
- Existing labels to start from: `labels_initial/*.txt`
- Review hints: `overlays/*.jpg` and `crops/*.jpg`
- Queue: `relabel_queue.csv`
- Image-level manifest: `image_manifest.csv`
- Class list: `classes.txt`

## Required outputs

1. Write one final label file per image:

   ```text
   labels_final/<same_image_stem>.txt
   ```

2. Use only this class:

   ```text
   0 damaged_tactile_block
   ```

3. If an image should be excluded instead of labeled, add a row to `exclude_candidates.csv` and explain why.

## Critical rules

- Do not copy red boxes blindly. Red boxes are model-prediction hints only.
- Use `labels_initial/` as a starting point, but correct boxes if they are too wide, too narrow, missing damage, or include unrelated areas.
- Final labels must describe **all visible damaged tactile block regions in the full image**, not just the crop.
- Do not edit files outside this package.
- Do not edit files in `images/`, `overlays/`, `crops/`, or `labels_initial/`.
- Only edit:
  - `labels_final/*.txt`
  - `exclude_candidates.csv`

## Label format

Each line:

```text
0 x_center_norm y_center_norm width_norm height_norm
```

All coordinates must be normalized to the package image dimensions and be in `[0, 1]`.
The package images are resized full-image copies with the same aspect ratio as the original sanitized images, so normalized YOLO labels remain compatible with the original image coordinate system.

## Validation

After labeling, run:

```bash
python3 validate_relabels.py
```

The validator checks:

- label file names match `images/*.jpg`
- YOLO field count and numeric format
- class id is `0`
- coordinates are within `[0, 1]`
- width/height are positive
