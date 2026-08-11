# Instructions for AI relabeling

## Goal

Create final YOLO labels for damaged tactile paving blocks in the full sanitized images.

## Class list

Use exactly one class:

```text
0 damaged_tactile_block
```

## YOLO label format

Write one object per line:

```text
<class_id> <x_center_norm> <y_center_norm> <width_norm> <height_norm>
```

Rules:

- `class_id` must be `0`.
- Coordinates are normalized to the full image width/height and must be in `[0, 1]`.
- Use spaces between fields.
- Use one `.txt` file per image in `labels_final/`, with the same stem as the image.

## Important labeling guidance

- The red boxes in `overlays/` are reference hints only. Do **not** blindly copy red boxes.
- Existing green/source boxes are available in `labels_initial/`. Use them as a starting reference, but correct them when needed.
- The final file in `labels_final/` must contain every damaged_tactile_block bbox visible in the full image.
- Label against the full image, not the crop image. Crops are only for inspection.
- If an image is too ambiguous, unsuitable, or should not be labeled, leave/omit the final label and add a row to `exclude_candidates.csv` with the reason.

## Suggested workflow

1. Open `image_manifest.csv`.
2. For each image, inspect the full image in `images/`, the initial label in `labels_initial/`, and related review rows in `relabel_queue.csv`.
3. Use `overlays/` and `crops/` only as hints to understand the questionable areas.
4. Write corrected full-image labels to `labels_final/`.
5. Record ambiguous/excluded cases in `exclude_candidates.csv`.

## Package image size note

The `images/` files are resized/recompressed full-image copies for GitHub. The aspect ratio is preserved, so YOLO normalized labels remain valid when mapped back to the original sanitized images. If your tool uses pixel coordinates, normalize using the package image width/height listed in `image_manifest.csv`.

## Files you may edit

Only edit:

- `labels_final/*.txt`
- `exclude_candidates.csv`

Do not edit the source images, initial labels, overlays, crops, or manifests.
