# AI Hub 513 VL/VS Dry-Run Inspection

작성일: 2026-05-14 KST
작업 범위: `VL1/VL2/VS1/VS2` validation zip pair 점검 스크립트 추가. 압축 해제, dataset 생성, 이미지 복사, 학습/평가는 수행하지 않음.

## 추가한 스크립트

`data_sources/scripts/inspect_aihub513_validation.py`

목적:

- `VL1.zip + VS1.zip` 또는 `VL2.zip + VS2.zip` 단일 pair만 검사한다.
- source image zip은 `ZipFile.infolist()`로 central directory만 읽는다.
- label zip은 `.json` member만 `archive.open()`으로 읽는다.
- zip 내부 이미지는 열거나 추출하지 않는다.
- 사람이 읽는 summary를 출력하고, `--json-summary`로 JSON summary를 선택적으로 쓸 수 있다.

## Tactile 파싱 규칙

기존 `data_sources/scripts/build_walksafe_kr_tactile.py`와 맞춘 규칙:

- tactile file: label member 경로에 `점자블럭`이 있거나 `description.facility == "2_09"`
- tactile annotation: `annotation.label_name == "점자블럭"`
- positive/defective box: `annotation.is_defect`가 `불량`으로 시작하고 bbox/polygon이 유효한 경우
- bbox는 기존 스크립트처럼 image width/height 범위로 clipping한 뒤 폭/높이가 각각 1px 초과일 때만 유효하게 센다.

계산 항목:

- full pair: label JSON count, image count, matched label filename count, missing image count, unmatched image count
- tactile subset: tactile file count, unique tactile filename count, defective/positive count, negative count, boxes count, missing tactile image count, unmatched image count vs tactile subset

## 사용 예시

기본 검색 루트에서 `VL1.zip`/`VS1.zip` 찾기:

```bash
python3 data_sources/scripts/inspect_aihub513_validation.py --pair 1 --download-root /path/to/validation/root
```

명시 경로 사용:

```bash
python3 data_sources/scripts/inspect_aihub513_validation.py \
  --pair 1 \
  --label-zip /path/to/VL1.zip \
  --image-zip /path/to/VS1.zip
```

JSON summary를 repo 밖에 쓰기:

```bash
python3 data_sources/scripts/inspect_aihub513_validation.py \
  --pair 1 \
  --download-root LOCAL_DATASETS/raw_downloads \
  --json-summary /tmp/aihub513_vl1_vs1_summary.json
```

## 검증 결과

도움말:

```bash
python3 data_sources/scripts/inspect_aihub513_validation.py --help
```

결과: 정상 출력.

구문 검사:

```bash
python3 -m py_compile data_sources/scripts/inspect_aihub513_validation.py
```

결과: 통과. 생성된 `__pycache__`는 삭제함.

실제 dry-run:

```bash
/usr/bin/time -f 'wall_time=%E max_rss_kb=%M' \
  python3 data_sources/scripts/inspect_aihub513_validation.py \
  --pair 1 \
  --download-root LOCAL_DATASETS/raw_downloads \
  --quiet
```

결과:

```text
Pair: VL1+VS1
Label zip: LOCAL_DATASETS/raw_downloads/VL1.zip
Image zip: LOCAL_DATASETS/raw_downloads/VS1.zip
Elapsed: 0.476s

Full pair matching
  Label JSON files: 25,998
  Labels with filename: 25,998
  Unique label filenames: 25,998
  Image files: 25,998
  Unique image filenames: 25,998
  Matched label filenames: 25,998
  Missing image count: 0
  Unmatched image count: 0

Tactile parsing
  Tactile label files: 1,038
  Tactile unique filenames: 1,038
  Defective/positive count: 0
  Negative count: 1,038
  Boxes count: 0
  Defective annotations: 0
  Invalid defective boxes: 0
  Matched tactile images: 1,038
  Missing tactile image count: 0
  Unmatched image count vs tactile subset: 24,960

wall_time=0:00.51 max_rss_kb=55612
```

해석:

- `VL1+VS1` full pair 기준 label filename과 source image filename은 모두 매칭됨.
- `VL1`의 tactile subset은 모두 양호 케이스로 파싱되어 positive box가 없음.
- tactile subset 기준 unmatched image는 전체 source image 중 tactile sample이 아닌 이미지 수다.
- 실행은 central directory와 label JSON만 읽었고, zip 내부 이미지는 추출하지 않음.

추가 smoke check:

```bash
python3 data_sources/scripts/inspect_aihub513_validation.py \
  --pair 2 \
  --download-root LOCAL_DATASETS/raw_downloads \
  --quiet
```

결과 요약:

- `VL2+VS2` full pair: label JSON `23,099`, image `23,099`, matched `23,099`, missing `0`, unmatched `0`
- tactile subset: tactile files `2,082`, positive `1,578`, negative `504`, boxes `5,326`, missing tactile image `0`
