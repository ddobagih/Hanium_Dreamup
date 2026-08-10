# WalkSafe v3 relabel task package (2026-05-21)

This package contains 32 `needs_bbox_relabel` review rows grouped into 20 unique sanitized images.

## Directory layout

- `images/`: full sanitized images to relabel.
- `labels_initial/`: source YOLO labels copied from the existing dataset, renamed to match each image stem.
- `labels_final/`: write the final YOLO labels here. Keep one `.txt` per image stem.
- `overlays/`: review overlay images. Red boxes are review references only.
- `crops/`: review crop images for context.
- `classes.txt`: class list.
- `image_manifest.csv`: unique-image manifest.
- `relabel_queue.csv`: original 32 review rows plus package-local paths.
- `exclude_candidates.csv`: record images that should be excluded instead of relabeled.

## Class

```text
0 damaged_tactile_block
```

## Required output

For every image in `images/`, create `labels_final/<same_image_stem>.txt`.
Each final label file must contain all visible `damaged_tactile_block` bounding boxes for the full image coordinate system, not just the crop/overlay region.

Do not modify source dataset files. Work only inside this package when producing final labels.

## GitHub package note

The full images, overlays, and crops in this task folder are resized/recompressed copies prepared for GitHub upload. Aspect ratio is preserved. YOLO normalized coordinates produced on these package images remain compatible with the original sanitized images.

Run the validator after creating final labels:

```bash
python3 validate_relabels.py
```
