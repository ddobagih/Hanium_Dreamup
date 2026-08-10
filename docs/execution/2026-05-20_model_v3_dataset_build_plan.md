# WalkSafe KR v3 dataset build dry-run 계획 (2026-05-20)

## 범위와 금지 사항

목표는 `datasets/walksafe_kr_v3`를 실제 생성하기 전에 필요한 dry-run 절차와 gate를 문서화하는 것이다.

이번 문서 작성에서는 다음을 하지 않았다.

- `datasets/walksafe_kr_v3` 폴더 생성
- 이미지/라벨 복사
- 라벨 파일 수정
- 새 학습 또는 smoke 학습 실행
- v2 test metric을 v3 평가 근거로 재사용

## 입력 manifest / 참조 문서

필수 입력:

- `data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv`
  - 총 61행
  - `walksafe_kr_v3_curation_manifest_2026-05-18.csv` 출처 40행
  - `walksafe_kr_v3_hard_negative_fp_review_2026-05-18.csv` 출처 21행
- `data_sources/manifests/walksafe_kr_v3_minbox_bbox_review_2026-05-20.md`
  - bbox 보정 대상 12행
  - min-box 보류 9행
  - small-object 포함 후보 3행
- `data_sources/manifests/walksafe_kr_v3_privacy_split_audit_2026-05-20.md`
  - source-level privacy/location audit 체크리스트
  - split leakage 방지 정책
  - v2 test failure 후보 사용 시 평가 오염 방지 규칙

참조 문서:

- `docs/execution/2026-05-20_model_data_mlops.md`
- `model/README.md`

## 현재 candidate 요약

| 구분 | rows | dry-run 처리 |
| --- | ---: | --- |
| `include_as_hard_negative` | 33 | empty label 후보. privacy/split gate 통과 후 hard negative로만 배치 가능 |
| `include_as_hard_negative_and_prioritize_threshold_augmentation_review` | 4 | empty label 후보. 임계값/augmentation 검토 우선순위로 표시 |
| `include_after_box_review` | 12 | 라벨 보정 완료 전 dataset 포함 금지 |
| `hold_until_min_box_policy_review` | 9 | min-box 정책 확정 전 dataset 포함 금지 |
| `include_with_small_object_augmentation` | 3 | bbox 최종 확인 및 small-object 정책 확정 후 포함 가능 |

주의: 위 표는 입력 CSV와 검토 문서 기준의 계획이다. 실제 파일 존재, 개인정보 audit, leakage group 검증은 아직 이 문서에서 실행하지 않았으므로 PASS로 보지 않는다.

## 학습 전 필수 gate

`datasets/walksafe_kr_v3` 생성 및 학습 전 아래 gate가 모두 닫혀야 한다.

| gate | 완료 조건 | 미충족 시 처리 |
| --- | --- | --- |
| source-level privacy/location audit | 원본 이미지 기준 얼굴, 차량번호, 신체 일부, 민감 위치, 위치 식별 배경, EXIF/메타데이터를 확인하고 `pass`/`needs_redaction`/`exclude_privacy`/`hold_unknown` 기록 | `hold_unknown`, `exclude_privacy`는 dataset 포함 금지. `needs_redaction`은 비식별화 정책 확정 전 포함 금지 |
| split leakage manifest | 모든 후보에 `leakage_group_key`, `v3_split`, 원본 source/split 매핑 기록 | 같은 group이 train/val/test에 섞이면 build 중단 |
| bbox 보정 | `include_after_box_review` 12행을 원본 이미지 기준으로 수동 보정하고 리뷰 기록 | 보정 완료 전 12행 포함 금지 |
| min-box 정책 | `hold_until_min_box_policy_review` 9행에 대해 `exclude`/`manual_relabel`/`small_object_include`/`keep_hold` 확정 | `keep_hold` 또는 기준 미달 후보 포함 금지 |
| small-object 정책 | small-object 3행의 bbox와 augmentation 방식을 최종 확인 | 확인 전 일반 positive로 조용히 포함 금지 |
| hard negative 정책 | hard negative는 라벨 파일을 비워 둔 YOLO negative image로 처리하고 positive 라벨을 만들지 않음 | false positive 박스를 라벨로 전환 금지 |
| v2 test metric 오염 방지 | v2 test/subset에서 온 후보가 v3 train에 들어가면 기존 v2 test metric 재사용 금지 명시 | 독립 v3 holdout/test 없이 개선 수치 주장 금지 |
| dataset validator | 생성 후 `validate_yolo_dataset.py` 오류 없음 | 오류가 있으면 dry-run/학습 중단 |
| train dry-run | `train_yolo.py --dry-run`이 설정만 출력하고 종료 | 실패하면 smoke 학습 금지 |
| 사용자 승인 | 위 gate 통과 후 smoke 학습 실행 여부를 사용자가 명시 승인 | 승인 전 smoke 학습 금지 |

## dry-run 절차

### 1. 입력 manifest 정합성 확인

실제 build 전 아래 항목을 스크립트 또는 수동 체크리스트로 확인한다.

- candidate CSV가 61행인지 확인한다.
- `policy_decision`별 count가 계획과 같은지 확인한다.
- 각 행의 `source_image`, `source_label`, `target_class_id`, `gt_box_xywhn`, `pred_box_xywhn`, `privacy_status`, `split_policy`를 읽을 수 있는지 확인한다.
- hard negative 행은 `gt_box_xywhn`가 비어 있고 positive label을 만들지 않는지 확인한다.
- positive 후보 행은 최종 라벨 원천이 `pred_box_xywhn`가 아니라 승인된 GT/수동 보정 bbox인지 확인한다.

이 단계는 파일 생성 없이 manifest만 읽는 dry-run이어야 한다.

### 2. 라벨 보정 대상 12행 처리 방식

대상: `policy_decision=include_after_box_review`, `manual_visual_label=true_damage_localization_error` 12행.

처리 원칙:

1. 원본 이미지를 열어 실제 파손/박리 영역만 포함하는 bbox를 수동 보정한다.
2. 기존 `gt_box_xywhn`는 출발점으로만 사용한다.
3. `pred_box_xywhn`는 세로 점자블록 strip 전체에 가까우므로 학습 라벨로 사용하지 않는다.
4. 같은 이미지에 복수 결함이 있으면 개별 bbox로 유지한다.
5. 보정 결과는 별도 리뷰 manifest에 candidate_id별로 남긴다.
6. 보정 완료 전에는 이 12행의 이미지/라벨을 `datasets/walksafe_kr_v3`에 복사하지 않는다.

### 3. min-box 9행 처리 방식

대상: `policy_decision=hold_until_min_box_policy_review`, `manual_visual_label=tiny_or_low_salience_defect_review_min_box_policy` 9행.

처리 원칙:

1. 사용자 경보 가치와 최소 bbox 크기 기준을 먼저 확정한다.
2. 원본 이미지 기준으로 결함이 명확하고 기준을 만족하는 행만 `small_object_include` 또는 `manual_relabel`로 전환한다.
3. 결함성이 불명확하거나 기준 미달이면 `exclude` 또는 `keep_hold`로 둔다.
4. `keep_hold` 상태인 행은 train/val/test 어느 split에도 넣지 않는다.
5. min-box 후보를 포함할 경우 small-object augmentation 정책과 평가 해석 한계를 함께 기록한다.

### 4. hard negative empty label 처리 방식

대상:

- `include_as_hard_negative` 33행
- `include_as_hard_negative_and_prioritize_threshold_augmentation_review` 4행

처리 원칙:

1. hard negative 이미지는 YOLO 형식에서 같은 stem의 `.txt` 라벨 파일을 생성하되 내용을 비운다.
2. false positive `pred_box_xywhn`는 라벨로 쓰지 않는다.
3. normal/weathered tactile block을 class `0 damaged_tactile_block` positive로 바꾸지 않는다.
4. empty label 파일 존재 여부를 validator로 확인한다.
5. hard negative도 privacy/location audit과 split leakage group 규칙을 동일하게 적용한다.

### 5. split leakage 방지

build dry-run에서 별도 split manifest를 먼저 만든다고 가정한다. 이 문서에서는 생성하지 않았다.

필수 컬럼 후보:

- `candidate_id`
- `source_dataset`
- `source_manifest`
- `source_split`
- `source_image`
- `policy_decision`
- `privacy_audit_status`
- `leakage_group_key`
- `v3_split`
- `label_action`
- `include_status`

규칙:

- 같은 장소, 연속 프레임, 파일 prefix, 촬영일+장소, source sequence, near-duplicate는 같은 `leakage_group_key`로 묶는다.
- 같은 `leakage_group_key`는 하나의 split에만 배정한다.
- hard negative와 positive가 같은 장소/시퀀스에서 나온 경우에도 split을 나누지 않는다.
- 애매하면 더 큰 group으로 묶는다.
- v2 test/subset에서 온 후보를 v3 train에 넣는 경우 같은 group의 이미지는 v3 val/test에 넣지 않는다.

### 6. v2 test metric 재사용 금지

`walksafe_kr_v3_curation_manifest_2026-05-18.csv` 출처 40행은 `walksafe_kr_v2`의 `test_subset`에서 온 후보다. 이 후보가 v3 train에 들어가면 기존 v2 test metric은 v3 모델 평가 근거로 재사용하지 않는다.

보고서에는 다음 취지를 명시한다.

- v2 test failure 후보는 hard example mining/curation 근거로만 사용했다.
- v3 성능은 독립적인 v3 holdout/test split으로 새로 평가한다.
- 기존 v2 test의 confidence, FP/FN, mAP 등은 v3 개선 수치로 재사용하지 않았다.

## 생성될 폴더 구조

실제 build가 승인되면 아래 구조를 목표로 한다. 이 문서 작성 중에는 생성하지 않았다.

```text
datasets/walksafe_kr_v3/
├── data.yaml
├── README.md
├── manifests/
│   ├── source_candidate_index.csv
│   ├── split_manifest.csv
│   ├── privacy_location_audit.csv
│   ├── bbox_relabel_review.csv
│   └── build_report.md
├── images/
│   ├── train/
│   ├── val/
│   └── test/
└── labels/
    ├── train/
    ├── val/
    └── test/
```

`data.yaml` 계획:

```yaml
path: datasets/walksafe_kr_v3
train: images/train
val: images/val
test: images/test
names:
  0: damaged_tactile_block
```

class `1..3` 한국 GT가 준비되지 않았으므로 v3는 class `0 damaged_tactile_block` 개선용으로만 둔다. 4-class metric은 v3 dataset build의 성공 기준으로 삼지 않는다.

## 검증 명령 계획

아래 명령은 `datasets/walksafe_kr_v3`가 실제 생성된 뒤에만 실행한다. 이 문서 작성 중에는 실행하지 않았다.

### dataset 구조/라벨 검증

```bash
python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v3/data.yaml
```

확인할 것:

- `data.yaml` 클래스 정의가 class `0`만 포함하는지
- train/val/test 이미지/라벨 폴더가 존재하는지
- 모든 이미지에 같은 stem의 라벨 파일이 있는지
- hard negative 라벨 파일이 존재하지만 비어 있는지
- positive 라벨 행이 `class x_center y_center width height` 5개 값인지
- class ID 범위와 bbox 좌표 범위가 정상인지

### train 설정 dry-run

```bash
python model/train_yolo.py \
  --data datasets/walksafe_kr_v3/data.yaml \
  --model yolo11n.pt \
  --epochs 1 \
  --imgsz 640 \
  --batch 8 \
  --name walksafe_kr_tactile_v3_dry_run \
  --dry-run
```

확인할 것:

- `data` 경로가 repo 내 `datasets/walksafe_kr_v3/data.yaml`로 해석되는지
- `dry-run`이므로 ultralytics import나 실제 학습이 실행되지 않는지
- 출력 설정이 의도한 smoke 후보 설정과 일치하는지

## smoke 학습 승인 조건

smoke 학습은 자동으로 진행하지 않는다. 아래 조건을 모두 만족한 뒤 사용자에게 별도 승인을 받아야 한다.

1. privacy/location audit 완료 및 제외/비식별화 대상 처리 완료.
2. bbox 보정 12행 처리 완료.
3. min-box 9행 처리 결정 완료.
4. split leakage 중복 없음 확인.
5. hard negative empty label 정책 반영 완료.
6. `validate_yolo_dataset.py` 통과.
7. `train_yolo.py --dry-run` 통과.
8. 사용자가 smoke 학습 실행을 명시 승인.

승인 후 후보 명령 예시는 다음과 같다. 승인 전에는 실행하지 않는다.

```bash
python model/train_yolo.py \
  --data datasets/walksafe_kr_v3/data.yaml \
  --model yolo11n.pt \
  --epochs 1 \
  --imgsz 640 \
  --batch 8 \
  --name walksafe_kr_tactile_v3_smoke
```

## 미완료 / 주의

- 이 문서는 dry-run 계획만 작성했다.
- `datasets/walksafe_kr_v3`는 생성하지 않았다.
- 이미지/라벨 복사와 라벨 보정은 하지 않았다.
- privacy/location source-level audit은 이 문서에서 실행하지 않았다.
- split manifest와 `leakage_group_key`는 아직 생성하지 않았다.
- 검증 명령 계획은 작성했지만 dataset 미생성 상태이므로 실행하지 않았다.
- smoke 학습은 gate 통과 후 사용자 승인 전까지 실행하지 않는다.
