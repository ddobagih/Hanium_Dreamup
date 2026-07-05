# AIHub 13-class label-first download plan

- Date: 2026-06-02 KST
- Goal: collect only the data needed to build the final WalkSafe single YOLO model after all custom classes are ready.
- Scope: AIHub 186, 189, 614, 71604, 557 plus existing AIHub 513.
- Rule: do not download full source archives first. Download labels/probes first, scan labels, then download only matching source partitions.
- Current training policy: no training now. Stop at data/filekey/label inspection and dataset-prep readiness.

## Final target classes

```text
person
bicycle
car
motorcycle
bus
truck
traffic light
normal_tactile_block
damaged_tactile_block
crosswalk
curb_step
uneven_sidewalk
e_scooter_obstruction
```

COCO supplies the first 7 classes. AIHub/custom sources supply the remaining 6 classes.

## Current local constraints

- `aihubshell` is available at `~/Downloads/aihub_shell/aihubshell`.
- `aihubshell -mode l -datasetkey ...` works without an API key for file tree inspection.
- Actual downloads require AIHub dataset approval and `AIHUB_API_KEY`.
- Disk free was about 135 GiB while COCO prepare-only download was still running. Do not start multi-100GB downloads until disk is rechecked.

## Team split

| Workstream | Owner | Output |
|---|---|---|
| 186 barrier-free | Agent A + parent integration | label/source partition plan for tactile/crosswalk/curb/uneven |
| 189 sidewalk + 614 PM | Agent B + parent integration | sidewalk/crosswalk/scooter candidate plan and e-scooter relabel policy |
| 71604 + 557 backup | Parent | backup/negative-source plan and low-priority rules |
| Tooling | Parent | aihubshell filetree manifests and safe dry-run downloader |

## Generated manifests/tooling

```text
data_sources/manifests/aihub_filetrees_20260602/186_barrier_free_filetree.txt
data_sources/manifests/aihub_filetrees_20260602/189_sidewalk_filetree.txt
data_sources/manifests/aihub_filetrees_20260602/614_pm_safety_filetree.txt
data_sources/manifests/aihub_filetrees_20260602/71604_delivery_robot_filetree.txt
data_sources/manifests/aihub_filetrees_20260602/557_road_maintenance_filetree.txt
data_sources/manifests/aihub_filetrees_20260602/513_tactile_filetree.txt
data_sources/manifests/aihub_filetrees_20260602/filekey_summary.json
scripts/aihub_label_first_download_20260602.sh
```

Safe dry run example:

```bash
PROFILE=186_labels scripts/aihub_label_first_download_20260602.sh
```

Actual download example after AIHub approval/API key:

```bash
AIHUB_API_KEY='***' DRY_RUN=0 PROFILE=186_labels \
  bash scripts/aihub_label_first_download_20260602.sh
```

Do not store the API key in git or shell scripts.

## Dataset-by-dataset plan

### 1. AIHub 186 — 베리어프리존(장애물 없는 생활공간) 주행영상

Purpose:

- Primary source for `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, `uneven_sidewalk`.
- Official page describes an image dataset for mobility-impaired users, 800k scale, with surface/spatial object labels.

Target label strings:

| Unified class | Candidate labels |
|---|---|
| `normal_tactile_block` | `braileblock_dot`, `braileblock_line` |
| `damaged_tactile_block` | `braileblock_dot_broken`, `braileblock_line_broken` |
| `crosswalk` | `planecrosswalk_normal`, `planecrosswalk_broken` |
| `curb_step` | `outcurb_rectangle`, `outcurb_slide`, `outcurb_rectangle_broken`, `outcurb_slide_broken`, `sidegap_out`; `stair_normal`, `stair_broken` only after review |
| `uneven_sidewalk` | `flatness_D`, `flatness_E`, `block_state_broken`, `paved_state_broken` |

First download profile:

```bash
PROFILE=186_labels scripts/aihub_label_first_download_20260602.sh
```

Filekeys included:

```text
Training labels:   35481,35482,35483,35484,35485,35486,35487,35488
Validation labels: 35410,35411,35412,35413,35414,35415,35416,35417
2024 add small/probe: 538782
```

Do not download sources until the label scan identifies useful partitions. Source candidates include `35407`, `35408`, `35409`, `35489`, `35418`-`35421`, and 2024 add large packages `538783`-`538791`, but these are large.

### 2. AIHub 189 — 인도보행 영상

Purpose:

- Primary source candidate for `crosswalk`, `uneven_sidewalk`, `e_scooter_obstruction` candidates, and possible tactile supplementation.

Important difference:

- The `aihubshell` file tree does not expose small separate label archives.
- Packages are grouped as `Bbox_*`, `Surface_*`, `Polygon_*`, `Depth_*` and are mostly 9-12 GiB each.
- Therefore this is not pure label-first. Use sample/light data if available in Firefox first; otherwise download one probe package per useful product type.

Initial probes, not all packages:

```bash
PROFILE=189_bbox_probe scripts/aihub_label_first_download_20260602.sh
PROFILE=189_surface_probe scripts/aihub_label_first_download_20260602.sh
PROFILE=189_polygon_probe scripts/aihub_label_first_download_20260602.sh
```

Filekeys:

```text
Bbox_1_new.zip    50043  10 GB
Surface_1.zip     49954  10 GB
Polygon_1_new.zip 49959  10 GB
```

Skip `Depth_*` for YOLO 13-class object detection unless later needed for depth research.

Target labels/attributes to search after probe download:

```text
crosswalk
sidewalk + damaged / surface damaged
braille_guide_blocks + normal/damaged
scooter
```

`e_scooter_obstruction` must not be auto-mapped from `scooter`; it requires relabeling for stationary/abandoned/path-blocking sidewalk scooters.

### 3. AIHub 614 — 개인형 이동장치 안전 데이터

Purpose:

- Main candidate pool for `e_scooter_obstruction` relabeling.
- Official page describes PM violation/object detection data with bbox/segmentation JSON and 600k+ images.

First download profile:

```bash
PROFILE=614_labels scripts/aihub_label_first_download_20260602.sh
```

Filekeys:

```text
Training labels:   56579,56580,56581,56582
Validation labels: 56587
```

Source filekeys are huge and must wait:

```text
TS1-TS4: 56583,56584,56585,56586  (92-101 GB each)
VS1:     56588                   (39 GB)
```

Scan goals:

- 킥보드/PM candidate labels.
- 보행자도로 통행 위반, 횡단보도 주행 위반, 정상 킥보드.
- Area/environment fields that reveal sidewalk/crosswalk context.

Use rule:

- Stationary/abandoned/path-blocking sidewalk scooter → positive `e_scooter_obstruction`.
- Moving scooter, road/bike-lane scooter, far scooter, rider-on scooter → negative or separate review bucket.

### 4. AIHub 71604 — 배송로봇 비도로 운행 데이터

Purpose:

- Backup source for `crosswalk`, `curb_step`, `uneven_sidewalk`, and e-scooter-like candidates.
- Official page describes 2D/3D robot non-road data. 2D labels include `crosswalk`, `stone`/curb-like, `twowheeler`, `obstacle`; metadata includes sidewalk/case/flatness context.

First download profile:

```bash
PROFILE=71604_2d_real_labels scripts/aihub_label_first_download_20260602.sh
```

Filekeys:

```text
TL_2D_실환경.zip 536760  6 GB
VL_2D_실환경.zip 536768  819 MB
```

Optional after useful scan:

```bash
PROFILE=71604_2d_all_labels scripts/aihub_label_first_download_20260602.sh
```

Do not start with 3D labels or sources. Use real 2D first because phone-facing Android detector is 2D RGB.

### 5. AIHub 557 — 지자체 도로 정비 AI 학습용 데이터

Purpose:

- Low-priority backup for `curb_step` and hard negative/road-surface experiments.
- Official page is vehicle road-maintenance oriented. It includes road cracks/holes and road-management objects such as curb/side gutter/manhole/barriers.

First download profile:

```bash
PROFILE=557_case2_labels scripts/aihub_label_first_download_20260602.sh
```

Filekeys:

```text
CASE2 Training labels:   34300,34301,34302
CASE2 Validation labels: 34345,34346
```

Avoid starting with CASE1 road-crack/road-hole labels unless needed as negatives. Avoid all source zips initially; many are 75-100 GB.

### 6. AIHub 513 — 보행 안전을 위한 도로 시설물 데이터

Purpose:

- Existing primary source for `normal_tactile_block`, `damaged_tactile_block`, `crosswalk`, `curb_step`, and road-facility backup labels.
- Current local source coverage already includes `TS8.zip`, `TS9.zip`, `VS1.zip`, `VS2.zip`, but local label coverage was only `TL8.zip`, `TL9.zip`, `VL1.zip`, `VL2.zip`.

All currently exposed label profile:

```bash
PROFILE=513_labels scripts/aihub_label_first_download_20260602.sh
```

Filekeys:

```text
Training labels:   62983,62984,62985,62986,62987,62988,62989,63002,63003,63004,63005,63006,63007,63008,63009,63010,63011,63012,63013,63014,63015,62990,62991,62992,62993,62994,62995,62996,62997,62998,62999,63000,63001
Validation labels: 63049,63050,63051,63052,63053
```

Approximate compressed label total from `aihubshell -mode l -datasetkey 513`: 1,760 MB.

## Execution order

1. Finish or pause current COCO prepare-only run depending on disk/network pressure.
2. In Firefox, log in to AIHub and approve/access these datasets: 513, 186, 189, 614, 71604, 557.
3. Get or confirm AIHub API key. Do not paste/store it in files.
4. Download labels/probes in this order:
   1. `513_labels`
   2. `186_labels`
   3. `614_labels`
   4. `557_case2_labels`
   5. `71604_2d_real_labels`
   6. `189_bbox_probe`, then `189_surface_probe`, then `189_polygon_probe` only if disk allows
5. Scan downloaded zips for target labels and image references.
6. Decide source filekeys partition-by-partition.
7. Download only matching source partitions.
8. Build dataset adapters per source.
9. Materialize final 13-class dataset.
10. Only then start 13-class training.

## Firefox / login notes

- Browser login/approval is an account operation. Do not enter credentials or secrets into chat.
- If an existing browser session is logged in, use it to approve downloads.
- If not logged in, the user must complete login/2FA/API-key issuance directly in Firefox.
- After approval, the CLI downloader can run with `AIHUB_API_KEY` supplied via environment variable.
