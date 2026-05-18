# WalkSafe v2 모델 현재 상태

작성 기준일: 2026-05-13

## 1. 현재 결론

v2 모델은 `TL8/TL9/TS8/TS9` 전체를 사용해 50 epoch 학습까지 완료됐다.

현재 작업 컴퓨터에도 로컬 `datasets/walksafe_kr_v2`와 `runs/detect/walksafe_kr_tactile_v2_full` 산출물이 존재한다. 단, 데이터셋 이미지/라벨, 학습 로그, `.pt` 가중치, `runs/` 산출물은 GitHub에 올리지 않는다.

## 2. v2 학습 결과

학습 run:

```text
runs/detect/walksafe_kr_tactile_v2_full
```

가중치:

```text
runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt
```

최종 epoch 결과:

| 항목 | 값 |
| --- | ---: |
| epoch | `50/50` |
| precision | `0.73656` |
| recall | `0.58380` |
| mAP50 | `0.66394` |
| mAP50-95 | `0.49194` |

test split 별도 검증 결과:

```text
runs/detect/runs/detect/walksafe_kr_tactile_v2_test_20260514
```

| 항목 | 값 |
| --- | ---: |
| images | `2,347` |
| instances | `3,979` |
| precision | `0.728` |
| recall | `0.581` |
| mAP50 | `0.657` |
| mAP50-95 | `0.481` |

해석:

- validation mAP50-95 `0.49194` 대비 test mAP50-95는 약 `-0.011` 하락했다.
- test 기준 성능 하락 폭은 작아서 v2를 기준선으로 동결할 수 있다.
- 다만 recall이 `0.581` 수준이라 실제 보행자 시점에서 미탐 가능성이 크다.
- 따라서 v2를 최종 서비스 모델로 보지 말고, AI Hub 513 외부 validation과 실패 프레임 분석을 계속 진행한다.
- 검증 중 Ultralytics가 일부 test JPEG를 로컬에서 `corrupt JPEG restored and saved`로 복구 저장했다. `datasets/`는 Git ignore 대상이며 GitHub에는 올라가지 않는다.

해석:

- v2는 기준선 모델로 보관할 만한 상태다.
- `precision`은 초기 모델 기준으로 나쁘지 않다.
- `recall`은 아직 낮은 편이라 실제 보행 영상에서는 놓치는 장면이 나올 수 있다.
- 다음 단계는 새 학습이 아니라 실제 보행자 시점 데이터에서 v2를 테스트하고 실패 프레임을 모으는 것이다.

## 3. v2 데이터셋 기준

사용 원본:

```text
TL8.zip
TL9.zip
TS8.zip
TS9.zip
```

생성 데이터셋:

```text
datasets/walksafe_kr_v2
```

보고된 분할:

| split | images | labels | nonempty labels | boxes |
| --- | ---: | ---: | ---: | ---: |
| train | `16,433` | `16,433` | `8,341` | `28,096` |
| val | `4,695` | `4,695` | `2,383` | `8,086` |
| test | `2,347` | `2,347` | `1,191` | `3,979` |

클래스:

```yaml
0: damaged_tactile_block
1: parked_kickboard_bicycle
2: construction_obstacle
3: pothole
```

## 4. 보관해야 할 파일

v2 기준선 비교를 위해 아래 파일은 삭제하지 않는다.

```text
runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt
runs/detect/walksafe_kr_tactile_v2_full/results.csv
runs/detect/walksafe_kr_tactile_v2_full/results.png
runs/detect/walksafe_kr_tactile_v2_full/confusion_matrix.png
logs/walksafe_kr_tactile_v2_full.log
datasets/walksafe_kr_v2/data.yaml
```

용량이 부족하면 `datasets/walksafe_kr_v2/images`, `datasets/walksafe_kr_v2/labels`, 원본 zip, 압축 해제 원본 폴더는 삭제할 수 있다.

단, 삭제 전에 아래 정보는 반드시 남긴다.

```text
사용 원본 파일명
파일키 또는 다운로드 출처
train/val/test 이미지 수
클래스 순서
최종 metric
best.pt 경로
```

## 5. AI Hub 513 추가 validation

AI Hub 513 validation은 v2 공식 검증을 보강하는 용도다.

2026-05-19 현재:

- `VL1+VS1` 200장 hard-negative subset은 평가와 상위 FP visual review가 완료됐다.
- conf `0.35` 기준 FP image `21/200`, FP detections `30`이다.
- positive box가 없는 subset이므로 recall/mAP가 아니라 정상 점자블록 false positive 평가로만 사용한다.
- `VL2+VS2` tactile positive subset 외부 검증은 class `0` 기준 완료됐다. 전체 class `1..3` 한국 GT validation은 아직 남아 있다.
- v3 후보 index는 failure 40행과 hard-negative FP 21행을 합쳐 61행이며, min-box 보류 9행은 정책 확정 전 학습에 넣지 않는다.

우선순위:

```text
1. VL1+VS1 전체 hard-negative 확장 여부 판단
2. AI Hub 159 소형 validation subset 확보
3. 직접 촬영 실패 프레임 후보 수집
```

주의:

- `VL*.zip`은 라벨이고 `VS*.zip`은 이미지다.
- 라벨만 받아서는 검증할 수 없다.
- validation zip과 추출 subset은 대용량이므로 추가 build/eval 전 디스크 gate를 먼저 확인한다.

## 6. AI Hub 159 다운로드 계획

AI Hub 159는 v2를 바로 다시 학습시키기 위한 데이터가 아니라, 실제 보행자 시점 테스트와 실패 프레임 추출용이다.

받지 말 것:

```text
전체 데이터셋
SEG 데이터
Training 전체
실내 in 데이터
교육활용 동영상
```

먼저 받을 것:

```text
2.Validation/BBOX/Average_stature/out
```

1차 다운로드 목록:

| 용도 | 파일 | 파일키 | 크기 |
| --- | --- | ---: | ---: |
| 라벨 | `라벨링데이터_0917_add/Average_stature/out/School.zip` | `40891` | `2KB` |
| 이미지 | `원천데이터/추가_비식별화이미지_0730/Average_stature/out/School.zip` | `40830` | `44MB` |
| 라벨 | `라벨링데이터_0917_add/Average_stature/out/Building_area.zip` | `40887` | `18KB` |
| 이미지 | `원천데이터/추가_비식별화이미지_0730/Average_stature/out/Building_area.zip` | `40826` | `293MB` |
| 라벨 | `라벨링데이터_0917_add/Average_stature/out/Bridge.zip` | `40886` | `61KB` |
| 이미지 | `원천데이터/추가_비식별화이미지_0730/Average_stature/out/Bridge.zip` | `40825` | `1.09GB` |

여유가 있으면 추가:

| 용도 | 파일 | 파일키 | 크기 |
| --- | --- | ---: | ---: |
| 라벨 | `라벨링데이터_0917_add/Average_stature/out/Park.zip` | `40889` | `240KB` |
| 이미지 | `원천데이터/추가_비식별화이미지_0730/Average_stature/out/Park.zip` | `40828` | `8.73GB` |
| 라벨 | `라벨링데이터_0917_add/Average_stature/out/Residential_area.zip` | `40890` | `565KB` |
| 이미지 | `원천데이터/추가_비식별화이미지_0730/Average_stature/out/Residential_area.zip` | `40829` | `11.86GB` |
| 라벨 | `라벨링데이터_0917_add/Average_stature/out/Market.zip` | `40888` | `956KB` |
| 이미지 | `원천데이터/추가_비식별화이미지_0730/Average_stature/out/Market.zip` | `40827` | `18.36GB` |

## 7. v3/v4로 넘어가는 흐름

1. v2 `best.pt`를 보관한다.
2. `data_sources/manifests/walksafe_kr_v3_curation_manifest_2026-05-18.csv`의 failure 후보 40행과 `data_sources/manifests/walksafe_kr_v3_hard_negative_fp_review_2026-05-18.csv`의 VL1+VS1 FP 후보 21행을 v3 후보로 둔다.
3. full test split failure sampling은 한 번에 이미지 저장까지 수행하지 않는다. CSV streaming, 최대 후보 수 제한, 이미지 저장 opt-in 방식으로 재시도한다.
4. 159번 실외 BBOX validation 일부를 다운로드한다.
5. v2 `best.pt`로 다운로드한 이미지에 추론을 돌린다.
6. 놓친 장면과 오탐 장면을 실패 프레임으로 분리한다.
7. 필요한 프레임만 라벨링한다.
8. 라벨링된 실패 프레임을 `datasets/walksafe_kr_v3_candidates`로 모은다.
9. v3 데이터셋을 새로 만들고 새 run 이름으로 학습한다.

권장 경로:

```text
datasets/walksafe_kr_v3_candidates/
datasets/walksafe_kr_v3/
runs/detect/walksafe_kr_tactile_v3_full/
logs/walksafe_kr_tactile_v3_full.log
```

## 8. 삭제 원칙

삭제 가능:

```text
AI Hub 원본 zip
압축 해제한 원본 폴더
임시 프레임 추출 폴더
대량 중간 산출물
```

삭제 금지:

```text
best.pt
last.pt
results.csv
results.png
confusion_matrix.png
data.yaml
학습 로그
사용 파일키/파일명 기록
```

삭제 전 확인:

```text
모델 가중치가 남아 있는가?
metric 기록이 남아 있는가?
어떤 원본을 썼는지 재현 가능한가?
다음 학습 데이터셋 이름이 v2와 분리되어 있는가?
```
