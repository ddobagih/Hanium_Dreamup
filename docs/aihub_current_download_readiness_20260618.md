# AIHub current download readiness check for unified 13-class training

- Date: 2026-06-18 KST
- Scope: read-only inspection of `/home/ddobagi/Downloads/한이음 드림업 데이터셋` and current local training assets.
- Safety: no download, no extraction, no deletion, no training was executed.

## Summary verdict

Current downloads are **not sufficient for a clean full 13-class training run yet**.

What is ready:

- COCO local data exists for the first 7 classes.
- AIHub 513 currently has label/source pairs for `TL8/TS8`, `TL9/TS9`, `VL1/VS1`, `VL2/VS2`.
- AIHub 189 has image+XML packages for bbox/surface/polygon smoke conversion.
- All checked zip files open successfully; no zero-byte zip or partial download was found.

Main blockers:

1. `datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml` does not exist yet.
2. Current generated dataset is only `datasets/walksafe_unified_coco_aihub513_tactile9_20260602`, not 13-class.
3. Current builder directly supports COCO + AIHub 513 JSON, but not AIHub 186/189/557/614/71604 adapters yet.
4. `crosswalk` has useful labels, but current source images are missing for the strongest 513/186 candidates.
5. `e_scooter_obstruction` cannot be auto-mapped from generic scooter/PM labels; it needs source images and manual/rule-based obstruction relabeling.
6. Disk free space is about 92 GiB, so 100 GB source zips are not safe to add without cleanup or external storage.

## Download inventory

| Check | Result |
|---|---:|
| Zip files under target download root | 112 |
| Total compressed zip size | ~454.83 GiB |
| Zero-byte zips | 0 |
| Partial download files | 0 |
| Zip open/member scan errors | 0 |
| Free disk on `/` | ~92 GiB |

Manifest written by the read-only inspector:

```text
data_sources/manifests/aihub_filetrees_20260602/current_downloads_inspection_20260618.json
```

## Current class coverage by source

### COCO

Local COCO is available:

| Split | Images |
|---|---:|
| train2017 | 118,287 |
| val2017 | 5,000 |

This covers:

```text
person, bicycle, car, motorcycle, bus, truck, traffic light
```

### AIHub 513: 보행 안전을 위한 도로 시설물 데이터

Current source-supported pairs:

| Pair | Status | Usable target classes found |
|---|---|---|
| `TL8.zip` + `TS8.zip` | label-image 100% matched | `normal_tactile_block`, `damaged_tactile_block` |
| `TL9.zip` + `TS9.zip` | label-image 100% matched | `damaged_tactile_block` |
| `VL1.zip` + `VS1.zip` | label-image 100% matched | `curb_step`, `normal_tactile_block` |
| `VL2.zip` + `VS2.zip` | label-image 100% matched | `normal_tactile_block`, `damaged_tactile_block`, `uneven_sidewalk`, `curb_step` |

Source-supported unique counts from current 513 pairs:

| Class | Annotation count | Image count |
|---|---:|---:|
| `normal_tactile_block` | 13,102 | 13,102 |
| `damaged_tactile_block` | 13,493 | 13,493 |
| `curb_step` | 15,649 | 7,337 |
| `uneven_sidewalk` | 21,942 | 6,216 |
| `crosswalk` | 0 | 0 |

513 label-only candidates also exist for `crosswalk` and more `curb_step`/`uneven_sidewalk`, but their source zips are not present:

- `crosswalk`: `TL21~TL24`, `VL3~VL4` labels exist; `TS21~TS24`, `VS3~VS4` source missing.
- Many of these source zips are listed as ~100 GB each, so they are not first-choice downloads with current free disk.

### AIHub 189: 인도보행 영상

These packages already include images and XML labels:

| Package | Images | XML labels | Main usable candidates |
|---|---:|---:|---|
| `Bbox_1_new.zip` | 21,522 JPG | 150 | COCO-like bbox classes, scooter candidate |
| `Surface_1.zip` | 9,558 JPG + 9,558 mask PNG | 130 | tactile, crosswalk, sidewalk damaged |
| `Polygon_1_new.zip` | 14,838 JPG | 300 | COCO-like polygon classes, scooter candidate |
| `Depth_001~005.zip` | nested depth zips | 0 | not needed for YOLO object detection now |

Useful counts observed:

| Class/candidate | Count | Notes |
|---|---:|---|
| `normal_tactile_block` | 2,243 polygons / 1,624 images | `braille_guide_blocks=normal` |
| `damaged_tactile_block` | 50 polygons / 37 images | weak/too small alone |
| `crosswalk` | 1,256 surface polygons / 827 images | bbox conversion policy needed |
| `uneven_sidewalk` | 1,027 surface polygons / 779 images | `sidewalk/alley=damaged` |
| scooter candidate | 150 shapes / 111 images | not automatically `e_scooter_obstruction` |

Readiness: good for **smoke conversion**, but an XML adapter and polygon/mask-to-bbox policy are required.

### AIHub 186: 베리어프리존 주행영상

Current state:

- Label zips exist and are strong for tactile/crosswalk/curb/uneven-sidewalk.
- No matching source image zips are currently present.
- Therefore current data cannot be materialized yet.

Priority source downloads from the existing plan:

| Priority | Filekey | Package | Size |
|---|---:|---|---:|
| P0 | 35421 | `2.서부산실외_베리어프리객체_1_220113_add.zip` validation source | 3 GB |
| P0 | 35419 | `1.동부산실외_베리어프리객체_1_220113_add.zip` validation source | 5 GB |
| P1 | 35409 | `2.서부산실외_베리어프리객체_1_220113_add.zip` training source | 28 GB |
| P1 | 35407 | `1.동부산실외_베리어프리객체_1_220113_add.zip` training source | 38 GB |

Readiness: high-value source, but **currently blocked by missing images**.

### AIHub 557: 지자체 도로 정비 AI 데이터

Current state:

- CASE1/CASE2 label zips exist and open successfully.
- No source image zips are present.
- CASE2 has abundant `경계석` candidates for `curb_step`, but it is road-maintenance/vehicle-view oriented.

Recommended first source only if needed:

| Filekey | Package | Size | Reason |
|---:|---|---:|---|
| 62179 | `VS03_지자체도로정비AI데이터_CASE2(도로관리객체).zip` | 2 GB | smallest CASE2 validation source |

Readiness: **not materializable now**; use as backup/curb hard-negative or supplemental source, not primary.

### AIHub 614: 개인형 이동장치 안전 데이터

Current state:

- `TL1~TL4`, `VL1` labels exist and open successfully.
- No source image zip is present.
- PM labels are code-heavy (`PM_code`, `area_code`) and need codebook/semantic mapping.
- PM presence alone must not become `e_scooter_obstruction` positive.

Potential source after policy confirmation:

| Filekey | Package | Size |
|---:|---|---:|
| 56588 | `VS1.zip` | 39 GB |

Readiness: **not usable for training yet**. Needs source images, codebook mapping, and obstruction relabeling.

### AIHub 71604: 배송로봇 비도로 운행 데이터

Current state:

- Relationship/QA label zips exist and open successfully.
- No source images are present.
- Outdoor relationship labels contain `obstacle`, `crosswalk`, `stone`, and small `twowheeler` candidates.
- Some `twowheeler` cases look closer to stopped/parked/no-rider candidates, but visual inspection is still required.

Readiness: **candidate only**. Needs outdoor source clips and manual filtering.

## Repo readiness

Current static checks:

- `scripts/check_walksafe_unified_training_plan_20260601.py --allow-missing-dataset` passes only with warning because 13-class `data.yaml` is missing.
- `datasets/walksafe_unified_coco_aihub513_tactile9_20260602/data.yaml` is 9-class and fails all-planned 13-class check.
- Existing tactile9 validator reports one missing validation label:

```text
missing label: labels/val/aihub_VS1_2_09_0_1_1_1_20211019_0000773557.txt
```

Tactile9 summary:

| Split | Images | Labels | Boxes |
|---|---:|---:|---:|
| train | 94,397 | 94,397 | 374,565 |
| val | 3,571 | 3,570 | 15,529 |
| test | 0 | 0 | 0 |

## What to get next

With current disk free space, avoid 100 GB zips first. Recommended next acquisition batch:

1. AIHub 186 validation outdoor source:
   - filekey `35421`, 3 GB
   - filekey `35419`, 5 GB
2. Optional small backup:
   - AIHub 557 filekey `62179`, 2 GB
3. Defer until after smoke/adapters:
   - AIHub 186 training sources `35409` 28 GB and `35407` 38 GB
   - AIHub 614 `VS1` 39 GB
   - AIHub 513 `TS21~TS24` / `VS3~VS4` because each is ~100 GB

## Required implementation before training

1. Fix builder root filtering so different datasets' `TL/VL` zips under `~/Downloads` cannot be accidentally paired with wrong `TS/VS` zips.
2. Add adapters:
   - AIHub 189 XML bbox/surface/polygon to YOLO bbox
   - AIHub 186 JSON to YOLO bbox after source download
   - Optional 557 CASE2 JSON adapter
   - Optional 614/71604 relabel pipelines for `e_scooter_obstruction`
3. Generate actual `datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml`.
4. Validate class coverage for all class IDs `0..12` in train/val before any real training.
5. Run dataset validator and static plan checker before training.

Minimum verification before training:

```bash
python data_sources/scripts/validate_yolo_dataset.py \
  datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml

python scripts/check_walksafe_unified_training_plan_20260601.py \
  --data-yaml datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml \
  --custom-class-scope all-planned

bash scripts/run_walksafe_unified_yolo26n_20260601.sh --print-only
```
