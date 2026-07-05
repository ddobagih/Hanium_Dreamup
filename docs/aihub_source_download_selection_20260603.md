# AIHub source download selection from downloaded labels

- Date: 2026-06-03 KST
- Goal: choose the smallest source archives needed after label-first/manual downloads.
- Rule: do not download all source archives. Start with smoke-sized source coverage and verify conversion/visualization first.
- Current disk check: about 114 GiB free on `/`, so 92-101 GiB archives are risky as first downloads.

## Current label/source status

| Dataset | Current status | Source decision |
|---|---|---|
| 189 인도보행 영상 | `Bbox_1_new`, `Surface_1`, `Polygon_1_new` already include images + XML labels | No new source download for smoke |
| 557 지자체 도로 정비 CASE2 | CASE2 labels downloaded; category `경계석` is abundant | Download smallest CASE2 source first |
| 186 베리어프리존 | labels show strong outdoor tactile/crosswalk/curb/uneven hits | Download outdoor validation sources first |
| 71604 배송로봇 업사이클링 | bbox relationship labels downloaded, but no images | Download only outdoor 원천데이터 clips if UI supports partial clip selection |
| 614 개인형 이동장치 | PM labels downloaded, but codebook/semantic mapping not confirmed | Defer huge sources or download only VS1 after confirmation |
| 513 보행 안전 도로시설물 | existing TS8/TS9/VS1/VS2 source zips are present and zip-open OK | Do not download more now |

## Recommended order

### 1. Do not download more for 189 yet

Already downloaded packages are mixed source+label:

- `Bbox_1_new.zip` — images + bbox XML
- `Surface_1.zip` — images + surface XML; includes `braille_guide_blocks`, `sidewalk`
- `Polygon_1_new.zip` — images + polygon XML

Use these for the first converter/visualization smoke test.

### 2. Download 557 validation source first

Download:

```text
VS03_지자체도로정비AI데이터_CASE2(도로관리객체).zip
filekey: 62179
size: 2 GB
```

Reason:

- Smallest CASE2 source.
- Paired with `VL03` labels.
- `VL03` labels contain many `경계석` candidates for `curb_step` smoke testing.

Optional after smoke succeeds:

```text
TS06_지자체도로정비AI데이터_CASE2(도로관리객체).zip  filekey 34308  17 GB
VS04_지자체도로정비AI데이터_CASE2(도로관리객체).zip  filekey 62180  15 GB
```

Avoid initially:

```text
TS07_지자체도로정비AI데이터_CASE2(도로관리객체).zip  filekey 34309  95 GB
```

### 3. Download 186 outdoor validation sources

Download:

```text
2.서부산실외 validation source  filekey 35421  3 GB
1.동부산실외 validation source  filekey 35419  5 GB
```

Reason:

- Outdoor labels have strong hits for tactile block, crosswalk, curb/step, uneven sidewalk.
- Validation sources are much smaller than training sources.

Optional after smoke succeeds:

```text
2.서부산실외 training source  filekey 35409  28 GB
1.동부산실외 training source  filekey 35407  38 GB
```

Avoid initially:

- Indoor sources unless indoor tactile/curb backup is needed.
- 2024 `TrafficWeak_AIData_zip1~zip9` until label mapping is confirmed.

### 4. For 71604 upcycling, get only outdoor source clips if possible

Current upcycling labels reference these image clips but images are not present:

```text
인도: Clip_703, Clip_706, Clip_702, Clip_707-1, Clip_707-2, Clip_HAL,WAL
골목: Clip_700, Clip_701, Clip_ALL
공원: Clip_709, Clip_710-1, Clip_708-1, Clip_710-2, Clip_710-3
```

If the AIHub upcycling UI allows partial source selection, download only those outdoor 원천데이터 clips. Avoid office/restaurant/exhibition/hall indoor clips at first.

If the UI only offers the whole upcycling source bundle, pause and check size before downloading.

### 5. Defer 614 source until code mapping or explicit approval

Smallest PM source:

```text
VS1.zip  filekey 56588  39 GB
```

Do not start `TS1~TS4` first:

```text
TS1 101 GB
TS2 101 GB
TS3 100 GB
TS4 92 GB
```

Reason:

- 614 labels use code fields such as `PM_code` and `area_code`.
- `e_scooter_obstruction` requires manual relabeling: moving/rider-on/road PM must not become positive obstruction automatically.
- Download `VS1` only after codebook mapping is confirmed or the user accepts a 39 GB relabeling source download.

## First safe source batch

If proceeding now, the first batch should be only:

```text
557 VS03      2 GB   filekey 62179
186 35421     3 GB
186 35419     5 GB
```

Approximate total: 10 GB.

This is small enough to validate source/label pairing before downloading 17 GB, 15 GB, 28 GB, 38 GB, or 39+ GB archives.
