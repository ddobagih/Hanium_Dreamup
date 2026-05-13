# 2026-05-14 Dataset Discovery

탐색 시각: 2026-05-14 00:44 KST

## 요약

- AI Hub `보행 안전을 위한 도로 시설물 데이터`의 요청 대상 zip 8개를 모두 `~/Downloads` 아래에서 찾았다.
- 발견 위치: `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터`
- 프로젝트 내부 `LOCAL_DATASETS/raw_downloads`에는 raw zip이 없다. 현재 비어 있다.
- 프로젝트의 `datasets/walksafe_kr_v1`, `datasets/walksafe_kr_v2`는 raw zip이 아니라 TL8/TL9/TS8/TS9에서 변환된 YOLO 데이터셋이다.
- 요청한 이름과 약간 다른 후보 패턴(`VL1`, `VL_1`, `VS1`, `VS_1`, `TL8`, `T_L8`, `TS8`, `T_S8` 등)으로도 확인했지만, AI Hub 관련 추가 zip 후보는 발견하지 못했다.

## 탐색 범위

- `/home/ddobagi`
- `/home/ddobagi/Downloads`
- `/home/ddobagi/Code/hanium-dreamup/LOCAL_DATASETS`
- `/home/ddobagi/Code/hanium-dreamup/datasets`
- `/home/ddobagi/Code/hanium-dreamup/data_sources`

홈 전체 zip 탐색에서는 `.cache`, Trash, `.npm`, `.cargo`, `.rustup`, `.git`, `node_modules` 같은 대량/캐시성 경로를 제외했다. 숨김 폴더는 일부 런타임/개발 캐시가 같이 검색되었지만, AI Hub 후보로 볼 수 있는 파일은 아래 8개뿐이었다. 파일 추출, 복사, 이동, 삭제는 하지 않았다.

## 발견된 AI Hub Raw Zip

| 파일 | 경로 | 크기 | bytes | 수정일 | 역할 추정 |
| --- | --- | ---: | ---: | --- | --- |
| `VL1.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/라벨링데이터/VL1.zip` | 47.4 MiB | 49,674,941 | 2026-05-13 18:18:29 KST | validation label |
| `VL2.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/라벨링데이터/VL2.zip` | 60.5 MiB | 63,354,839 | 2026-05-13 18:18:35 KST | validation label |
| `VS1.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS1.zip` | 100.1 GiB | 107,387,942,790 | 2026-05-13 20:57:15 KST | validation source image |
| `VS2.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터/VS2.zip` | 100.1 GiB | 107,388,232,773 | 2026-05-13 23:30:59 KST | validation source image |
| `TL8.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/라벨링데이터/TL8.zip` | 37.8 MiB | 39,607,029 | 2026-05-11 21:25:47 KST | training label |
| `TL9.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/라벨링데이터/TL9.zip` | 40.8 MiB | 42,731,536 | 2026-05-11 21:25:51 KST | training label |
| `TS8.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터/TS8.zip` | 100.1 GiB | 107,383,528,530 | 2026-05-12 01:51:36 KST | training source image |
| `TS9.zip` | `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터/TS9.zip` | 100.1 GiB | 107,389,959,564 | 2026-05-12 04:26:18 KST | training source image |

상위 디렉터리 크기:

| 경로 | 크기 |
| --- | ---: |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터` | 401 GiB |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training` | 201 GiB |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/원천데이터` | 201 GiB |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/1.Training/라벨링데이터` | 79 MiB |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation` | 201 GiB |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/원천데이터` | 201 GiB |
| `/home/ddobagi/Downloads/119.보행 안전을 위한 도로 시설물 데이터/01.데이터/2.Validation/라벨링데이터` | 108 MiB |

## 프로젝트 내부 상태

`LOCAL_DATASETS/raw_downloads`:

- 경로: `/home/ddobagi/Code/hanium-dreamup/LOCAL_DATASETS/raw_downloads`
- 상태: 비어 있음
- 수정일: 2026-05-14 00:22:51 KST
- 의미: 프로젝트 내부에는 AI Hub raw zip이 복사되어 있지 않다.

`LOCAL_DATASETS` 주요 링크:

| 경로 | 대상 | 역할 |
| --- | --- | --- |
| `/home/ddobagi/Code/hanium-dreamup/LOCAL_DATASETS/01_aihub513_tactile_v2_yolo_dataset` | `../datasets/walksafe_kr_v2` | 변환된 AI Hub 513 YOLO v2 데이터셋 |
| `/home/ddobagi/Code/hanium-dreamup/LOCAL_DATASETS/02_aihub513_tactile_v1_yolo_dataset` | `../datasets/walksafe_kr_v1` | 변환된 AI Hub 513 YOLO v1 데이터셋 |
| `/home/ddobagi/Code/hanium-dreamup/LOCAL_DATASETS/03_public_smoke_walksafe_v1_dataset` | `../datasets/walksafe_v1` | 공개 smoke dataset skeleton |

주의: `LOCAL_DATASETS/README_LOCAL.txt`의 "raw zip not found" 메모는 현재 탐색 결과와 다르다. 현재는 `~/Downloads`에서 raw zip 8개가 발견되었고, `LOCAL_DATASETS/raw_downloads`만 비어 있다.

## 변환 데이터셋과 Raw Zip 구분

| 데이터셋 | 경로 | 크기 | 수정일 | 파일 수 | 역할 |
| --- | --- | ---: | --- | ---: | --- |
| `walksafe_kr_v1` | `/home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_v1` | 49 GiB | 2026-05-12 11:40:19 KST | train 4,200 / val 1,200 / test 600 images | TL8/TL9/TS8/TS9 기반 변환 YOLO 데이터셋 |
| `walksafe_kr_v2` | `/home/ddobagi/Code/hanium-dreamup/datasets/walksafe_kr_v2` | 199 GiB | 2026-05-12 13:11:07 KST | train 16,433 / val 4,695 / test 2,347 images | TL8/TL9/TS8/TS9 기반 변환 YOLO 데이터셋 |
| `walksafe_v1` | `/home/ddobagi/Code/hanium-dreamup/datasets/walksafe_v1` | 72 KiB | 2026-05-12 11:34:15 KST | 현재 실제 이미지 0개 | 공개 smoke dataset skeleton |

두 AI Hub 변환 데이터셋의 `BUILD_SUMMARY.md` 기준 source는 모두 training zip인 `TL8.zip`, `TL9.zip`, `TS8.zip`, `TS9.zip`이다. 즉 현재 `walksafe_kr_v1/v2`는 validation zip(`VL1/VL2/VS1/VS2`)을 아직 변환 산출물로 포함했다고 볼 근거가 없다.

## 후보 부재

- 프로젝트 내부 `LOCAL_DATASETS`, `datasets`, `data_sources`에는 `VL1.zip`, `VL2.zip`, `VS1.zip`, `VS2.zip`, `TL8.zip`, `TL9.zip`, `TS8.zip`, `TS9.zip` raw 파일이 없다.
- `~/Downloads` 외 위치에서 같은 AI Hub zip의 중복본은 발견하지 못했다.
- 비슷한 이름 후보(`VL_1.zip`, `Validation_Label_1.zip`, `VS_1.zip`, `T_L8.zip`, `T_S8.zip` 등으로 잡힐 수 있는 패턴)는 발견하지 못했다.
