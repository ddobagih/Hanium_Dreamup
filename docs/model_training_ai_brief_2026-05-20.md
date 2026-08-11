# WalkSafe 모델 학습 현황 AI 인계 브리핑 (2026-05-20)

이 문서는 다른 AI에게 현재 모델 학습 상태를 그대로 설명하기 위한 한국어 브리핑이다. 아래 내용은 기존 문서와 manifest 기준 요약이며, 명시된 미실행 항목을 완료로 해석하면 안 된다.


## 0. 2026-05-20 KST 최신 사용자 수동 검수 결정

- privacy_hold 39개는 민감정보 없음으로 전부 해제됐고, 최종 블러는 불필요하다.
- hard-negative 성격 묶음은 파손 점자블럭 없음으로 확인되어 빈 라벨 후보로 정리한다.
- 사용자가 본 파손 탐지는 전반적으로 잘 맞고 90% 이상으로 보이나, 이는 정성/spot-check 판단이다. 독립 holdout/test metric이나 공식 성능 수치로 쓰면 안 된다.
- 독립 holdout/test 부재, small/far 50행 제외, min-box 9행 제외, bbox 12행 수동 보정 필요 상태는 유지된다.

## 1. 프로젝트 모델 목표

- 서비스 목표: 시각장애인 보행 지원을 위해 보행 위험 요소를 탐지하고 1초 이내 경보를 제공한다.
- 탐지 대상 계약 class:
  - `0: damaged_tactile_block` 파손/단절 점자블록
  - `1: parked_kickboard_bicycle` 방치 킥보드/자전거
  - `2: construction_obstacle` 공사 구조물/적치물
  - `3: pothole` 포트홀/노면 파손
- README의 목표는 객체 인식 정확도 90% 이상, 경보 지연 1초 이내다.
- 현재 실제 한국 GT 기반 학습은 class `0` 점자블록 중심이다. `data.yaml`은 4개 클래스 계약을 유지하지만, v1/v2 metric은 실질적으로 class `0` baseline으로 봐야 한다.

## 2. 현재 v2 모델 상태

v2는 AI Hub 513 `TL8/TL9/TS8/TS9` 전체를 사용해 50 epoch 학습이 완료된 현재 기준선 모델이다. 최종 서비스 모델로 확정된 것은 아니며, recall이 낮아 실제 보행자 시점에서 미탐 가능성이 있다.

### v2 산출물 경로

```text
runs/detect/walksafe_kr_tactile_v2_full
runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt
runs/detect/walksafe_kr_tactile_v2_full/weights/last.pt
runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx
logs/walksafe_kr_tactile_v2_full.log
datasets/walksafe_kr_v2/data.yaml
```

주의: 데이터셋 이미지/라벨, `runs/`, `.pt`, `.onnx`, 로그 산출물은 GitHub에 올리지 않고 로컬 보관한다.

### v2 hash

| artifact | sha256 |
| --- | --- |
| `best.pt` | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |
| `best.onnx` | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |

### v2 metric

Validation 기준 `best.pt` 결과:

| metric | value |
| --- | ---: |
| epoch | `50/50` |
| best epoch | `50` |
| precision | `0.73656` |
| recall | `0.58380` |
| mAP50 | `0.66394` |
| mAP50-95 | `0.49194` |

Test split 별도 검증 결과:

| metric | value |
| --- | ---: |
| images | `2,347` |
| instances | `3,979` |
| precision | `0.728` |
| recall | `0.581` |
| mAP50 | `0.657` |
| mAP50-95 | `0.481` |

해석:

- v2는 v1보다 validation mAP50-95가 약 `+0.05315`, mAP50이 약 `+0.05652` 개선됐다.
- validation mAP50-95 `0.49194` 대비 test mAP50-95 `0.481`로 하락 폭은 약 `-0.011`이다.
- 목표 mAP 0.9와는 거리가 있어 현재 수치를 서비스 완성 성능으로 말하면 안 된다.

## 3. 데이터셋 v1/v2 규모

### `datasets/walksafe_kr_v1`

AI Hub 513 `TL8/TL9/TS8/TS9`에서 균형 샘플링으로 만든 1차 데이터셋이다.

| split | images | labels | positive images | negative images | boxes |
| --- | ---: | ---: | ---: | ---: | ---: |
| train | 4,200 | 4,200 | 2,100 | 2,100 | 7,072 |
| val | 1,200 | 1,200 | 600 | 600 | 2,037 |
| test | 600 | 600 | 300 | 300 | 1,026 |

### `datasets/walksafe_kr_v2`

AI Hub 513 `TL8/TL9/TS8/TS9` 전체를 사용한 2차 데이터셋이다.

| split | images | labels | positive images | negative images | boxes |
| --- | ---: | ---: | ---: | ---: | ---: |
| train | 16,433 | 16,433 | 8,341 | 8,092 | 28,096 |
| val | 4,695 | 4,695 | 2,383 | 2,312 | 8,086 |
| test | 2,347 | 2,347 | 1,191 | 1,156 | 3,979 |

주의: 로컬 파일 스캔 기준 v2 val boxes는 `8,086`개이고, Ultralytics 학습 로그 validation instances는 `8,085`개로 1개 차이가 있었다.

## 4. class별 데이터 상태

| class_id | class_name | 현재 상태 |
| ---: | --- | --- |
| 0 | `damaged_tactile_block` | AI Hub 513 한국 GT 중심으로 v1/v2 학습 완료. v3는 이 class 개선용 후보 정리 단계 |
| 1 | `parked_kickboard_bicycle` | 한국 GT 미확보. v4 계획 문서에서 positive 500장 / negative 300장 1차 목표만 정의 |
| 2 | `construction_obstacle` | 한국 GT 미확보. v4 계획 문서에서 positive 500장 / negative 300장 1차 목표만 정의 |
| 3 | `pothole` | 한국 GT 미확보. v4 계획 문서에서 positive 500장 / negative 300장 1차 목표만 정의 |

class `1..3` 한국 GT validation/test가 준비되기 전까지 4-class metric은 산출하지 않는다. 현재 repo와 `/home/ddobagi/Downloads` 검색 기준 AI Hub 159 `Average_stature/out` 우선 zip 후보는 확인되지 않았다.

## 5. ONNX/export/latency 상태

- v2 `best.onnx` export와 full metric equivalence는 완료됐다.
- test split 기준 PT mAP50-95는 `0.481`, ONNX mAP50-95는 `0.482`로 계획 기준을 만족했다.
- test split 120장 latency 측정 결과:
  - PT p95: `12.6493ms`
  - ONNX Runtime CPU p95: `58.1289ms`
- 현재 backend ready artifact는 계속 `.pt`로 둔다.
- browser/ONNX Runtime Web latency와 모바일/실폰 latency는 아직 실행하지 않았다.

## 6. hard-negative / external validation 상태

- AI Hub 513 `VL2+VS2` tactile positive subset 외부 검증은 class `0` 기준 완료됐다.
- AI Hub 513 `VL1+VS1` hard-negative 200장 subset 평가와 상위 FP visual review가 완료됐다.
  - conf `0.35` 기준 FP image `21/200`, FP detections `30`
  - positive box가 없는 subset이므로 recall/mAP가 아니라 정상 점자블록 false positive 평가로만 사용한다.
- `VL1+VS1` 전체 hard-negative inference는 아직 하지 않았다.
- 실제 보행자 시점 영상/직접 촬영 기반 외부 검증은 아직 충분히 완료되지 않았다.
- class `1..3` 한국 GT validation은 아직 없다.

## 7. v3 후보 / sampling / review queue 현황

### v3 후보 61행

v3 candidate index는 총 61행이다.

| source | rows |
| --- | ---: |
| v2 test failure 기반 후보 | 40 |
| `VL1+VS1` hard-negative FP review 후보 | 21 |
| 합계 | 61 |

policy별 count:

| policy_decision | rows |
| --- | ---: |
| `include_as_hard_negative` | 33 |
| `include_after_box_review` | 12 |
| `hold_until_min_box_policy_review` | 9 |
| `include_with_small_object_augmentation` | 3 |
| `include_as_hard_negative_and_prioritize_threshold_augmentation_review` | 4 |

중요: policy상 train 후보가 있더라도 split leakage 검증과 학습 manifest 확정 전 실제 학습 투입은 금지다. privacy_hold 39행은 사용자 수동 검수로 해제됐지만, `hold_until_min_box_policy_review` 9행은 v3.0 제외이고 `include_after_box_review` 12행은 원본 이미지 기준 수동 bbox 보정 전 포함하지 않는다.

### 200장 failure sampling 결과

v2 test split에서 200장 bounded sampling을 실행했다. 이미지 파일은 생성하지 않았고 CSV 후보만 만들었다.

| 항목 | 값 |
| --- | ---: |
| sample images | 200 |
| positive / negative | 100 / 100 |
| GT boxes | 334 |
| predictions | 259 |
| matched GT boxes | 190 |
| missed GT boxes | 144 |
| negative images with prediction | 12 |
| candidate rows written | 135 |
| generated image files | 0 |

bucket별 결과:

| bucket | rows written |
| --- | ---: |
| `false_positive_extra_box` | 47 |
| `false_positive_normal_tactile` | 18 |
| `missed_defect` | 20 |
| `small_or_far` | 50 |

full test split 2,347장 전체 sampling은 아직 하지 않았다.

### 통합 review queue 196행

v3 candidate 61행과 200장 sampling 후보 135행을 합쳐 review queue 196행을 만들었다.

| source | rows |
| --- | ---: |
| `v3_candidate_index` | 61 |
| `sampling_200` | 135 |
| 합계 | 196 |

recommended review action:

| action | rows |
| --- | ---: |
| `hard_negative_review` | 55 |
| `box_relabel_review` | 12 |
| `min_box_policy_review` | 9 |
| `positive_review` | 23 |
| `hold` | 50 |
| `manual_review` | 47 |

v3 include decision:

| decision | rows |
| --- | ---: |
| `include_candidate` | 40 |
| `needs_manual_review` | 97 |
| `hold` | 59 |

주의: `include_candidate`는 policy상 후보라는 뜻일 뿐이며, privacy/location audit과 split group 확인 전 실제 학습 가능 상태가 아니다.

## 8. split leakage / privacy 상태

- v3 61행에 대해 파일명 기반 `leakage_group_key` 초안을 만들었다.
  - 입력/출력 후보 행: 61
  - leakage group 수: 46
  - 2개 이상 후보를 포함한 group 수: 11
  - 중복 `source_image_basename` 종류 수: 8
  - 중복 basename 총 행 수: 16
- proposed split 초안:
  - `train_candidate`: 40행, v2 test failure 기반 hard example mining 후보
  - `hard_negative_pool`: 21행, `VL1+VS1` hard-negative FP review 후보
- 이 group은 파일명 기반 초안이다. 원본 이미지 시각 검토, EXIF/메타데이터, 위치/시퀀스 검토, pHash/embedding near-duplicate 검사를 대체하지 않는다.
- v2 test failure 후보를 v3 train에 넣으면 기존 v2 test metric은 v3 평가 근거로 재사용하지 않는다.

## 9. 현재 하고 있는 작업: v3 학습 전 gate 닫기

현재 단계의 핵심은 새 학습이 아니라 `datasets/walksafe_kr_v3` 생성 전 gate를 닫는 것이다.

현재 gate 상태:

| gate | 상태 | 이유 |
| --- | --- | --- |
| candidate index 검산 | PASS | 61행과 summary count 일치 |
| min-box 정책 | EXCLUDED | 사용자 정책상 9행 v3.0 제외 |
| bbox 보정 | PENDING | 12행 `manual_relabel` 필요 |
| privacy/location audit | PASS(사용자 수동 검수) | privacy_hold 39개 민감정보 없음, 최종 블러 불필요 |
| split leakage 정책 | PENDING | 파일명 기반 초안만 있음. 실제 `leakage_group_key` 검증 필요 |
| 200장 sampler | PASS | 이미지 저장 없이 135행 후보 생성 |
| full sampling | PENDING | 2,347장 전체 미실행 |
| class `1..3` GT | BLOCKED | 원본/라벨 미확보 |
| 새 v3 학습 | BLOCKED | 위 gate 해소 전 실행 금지 |

## 10. 아직 안 한 것

다음 항목은 아직 완료되지 않았다.

- `datasets/walksafe_kr_v3` 생성
- v3 dataset 이미지/라벨 복사
- v3 라벨 파일 수정 또는 수동 bbox 보정 반영
- v3 smoke/full 학습
- v3 독립 holdout/test 평가
- v3 또는 v4 4-class metric 산출
- class `1..3` 한국 GT 수집/라벨링/validation
- full test split 2,347장 failure sampling
- `VL1+VS1` 전체 hard-negative inference
- browser/ONNX Runtime Web latency
- 모바일/실폰 latency
- 대형 AI Hub 159 다운로드

## 11. 다음 해야 할 일

1. bbox 보정 대상 12행을 원본 이미지 기준으로 수동 보정하고 별도 리뷰 manifest에 남긴다.
2. 사용자 검수로 파손 없음이 확인된 hard-negative 빈 라벨 후보를 split/manifest에 반영한다.
3. small/far 50행과 min-box 9행은 v3.0 제외 상태를 유지한다.
4. 파일명 기반 leakage group 초안을 시각 검토, EXIF/메타데이터, near-duplicate 검사로 보강한다.
5. 200장 sampling 후보 135행을 수동 triage해 v3 후보 편입 여부를 결정한다.
6. 독립 v3 holdout/test 후보를 확보해 v2 test metric 재사용 문제를 피한다.
7. 위 gate가 모두 닫힌 뒤에만 `datasets/walksafe_kr_v3` 생성 절차를 만든다.
8. v3 dataset 생성 후 `python model/validate_yolo_dataset.py --data datasets/walksafe_kr_v3/data.yaml`을 통과시킨다.
9. 사용자 승인 후에만 v3 smoke 학습을 검토한다.
10. class `1..3`은 직접 촬영 또는 AI Hub 159 소형 subset 확인부터 시작하고, 4-class metric은 한국 GT validation/test 확보 후에만 보고한다.

## 12. 과대해석 금지 문구

다른 AI는 아래 문구를 반드시 지켜야 한다.

- 현재 성능은 class `0: damaged_tactile_block` 중심 baseline이다. 4개 클래스 서비스 모델 성능으로 말하면 안 된다.
- v2 test mAP50-95 `0.481`은 목표 0.9에 도달한 수치가 아니다.
- v3 후보 61행, 200장 sampling 135행, review queue 196행은 학습 데이터 확정본이 아니라 검토 대상이다.
- `include_candidate`는 바로 학습 투입 가능하다는 뜻이 아니다.
- v2 test failure 후보를 v3 학습에 쓰면 기존 v2 test metric을 v3 개선 근거로 재사용하면 안 된다.
- ONNX export는 되었지만 backend ready artifact는 현재 `.pt`이고, browser/mobile latency는 아직 검증되지 않았다.
- class `1..3`은 계획 단계이며 한국 GT 기반 metric이 없다.
- 실행하지 않은 항목을 완료했다고 쓰면 안 된다.

## 13. 참조 입력 문서

- `README.md` 모델 섹션
- `docs/model_training_status.md`
- `docs/model_v2_status.md`
- `docs/execution/2026-05-20_model_data_mlops.md`
- `docs/execution/2026-05-20_model_v3_dataset_build_plan.md`
- `data_sources/manifests/walksafe_kr_v3_review_queue_summary_2026-05-20.json`
- `data_sources/manifests/walksafe_kr_v3_leakage_split_plan_2026-05-20.md`
- `data_sources/manifests/walksafe_kr_v4_class123_data_plan_2026-05-20.md`

---

## 14. 2026-05-20 추가 병렬 실행 결과

이 섹션은 위 브리핑 초안 작성 이후 추가로 병렬 실행된 자동 audit/manifest 결과다. 여전히 새 학습, 이미지/라벨 수정, `datasets/walksafe_kr_v3` 생성은 하지 않았다.

### 14.1 privacy/location 자동 보조 audit

review queue 196행의 원본 이미지 접근성과 기초 privacy/location 신호를 자동 확인했다.

| 항목 | 값 |
| --- | ---: |
| audit input rows | 196 |
| output rows | 196 |
| image exists | 196 / 196 |
| EXIF present | 75 / 196 |
| Haar cascade face candidate >= 1 | 60 / 196 |
| plate OCR/detection | 수행 안 함, `unavailable_not_run` |
| filename scene/date signal | 196 / 196 |
| contact sheets | 6개, ignored `runs/review/...` 아래 생성 |

주의: 이 결과는 자동/보조 audit이다. Haar cascade 얼굴 후보는 false positive/false negative가 가능하고, 원본 고해상도 인간 검수를 대체하지 않는다. 따라서 privacy/location gate는 아직 완전히 닫히지 않았다.

### 14.2 label decision manifest

review queue 196행에 대해 v3 label action과 gate status 초안을 만들었다.

`v3_label_action` count:

| action | rows |
| --- | ---: |
| `hold_small_or_far_policy` | 50 |
| `manual_duplicate_or_box_review_required` | 47 |
| `empty_label_candidate` | 37 |
| `manual_positive_review_required` | 23 |
| `manual_hard_negative_review_required` | 18 |
| `manual_bbox_relabel_required` | 12 |
| `hold_min_box_policy` | 9 |
| total | 196 |

`final_gate_status` count:

| status | rows |
| --- | ---: |
| `blocked_by_small_or_far_policy` | 50 |
| `blocked_by_manual_duplicate_or_box_review` | 47 |
| `blocked_by_privacy_split_audit` | 40 |
| `blocked_by_manual_positive_review` | 20 |
| `blocked_by_manual_hard_negative_review` | 18 |
| `blocked_by_relabel` | 12 |
| `blocked_by_min_box_policy` | 9 |
| total | 196 |

해석: 즉시 학습 가능한 행은 0으로 본다. policy상 후보 40행도 privacy/location audit과 split-group 확인 전까지 `blocked_by_privacy_split_audit` 상태다. `pred_box_xywhn`은 라벨로 쓰지 않는다.

### 14.3 near-duplicate / split audit 보강

review queue 196행에 대해 64-bit dHash 기반 near-duplicate 후보를 자동 산출했다.

| 항목 | 값 |
| --- | ---: |
| input/output rows | 196 / 196 |
| hash empty count | 0 |
| unique hash count | 120 |
| exact hash pair count | 133 |
| Hamming distance <= 5 candidate pairs | 133 |
| near-duplicate groups | 44 |
| near-duplicate rows | 120 |
| filename leakage group으로 완전히 커버되지 않는 group | 37 |

split risk status:

| status | rows |
| --- | ---: |
| `covered_by_existing_leakage_group` | 14 |
| `new_near_duplicate_candidate_not_in_filename_group` | 106 |
| `no_near_duplicate_candidate` | 76 |

해석: 파일명 기반 leakage group만으로는 부족하다. v3 split을 확정하기 전에 dHash 후보 중 `new_near_duplicate_candidate_not_in_filename_group` 106행을 수동 확인하고, confirmed near-duplicate는 같은 split으로 묶거나 평가 split에서 제외해야 한다.

### 14.4 현재 최종 상태

- v2 baseline은 동결 상태다.
- v3는 아직 학습 전이다.
- v3 review queue는 196행까지 확장됐다.
- 자동 privacy audit, label decision manifest, dHash near-duplicate audit까지 완료됐다.
- 하지만 자동 audit 결과는 최종 인간 검수/정책 결정을 대체하지 않으므로 v3 학습은 여전히 blocked 상태다.

다음 실제 작업은 아래 4가지다.

1. `manual_bbox_relabel_required` 12행의 bbox를 실제로 보정한다.
2. 사용자 검수로 파손 없음이 확인된 hard-negative 빈 라벨 후보를 split/manifest에 반영한다.
3. `hold_min_box_policy` 9행과 `hold_small_or_far_policy` 50행은 v3.0 제외 상태를 유지한다.
4. dHash near-duplicate 후보 120행을 수동 확인해 split manifest를 최종화한다.
5. 독립 holdout/test 후보를 확보한다.

---

## 15. 첨부 계획 실행 결과: PM / Privacy / Label / Split gate 산출물

사용자가 첨부한 `walksafe_v3_vision_ai_plan.md` 기준으로 병렬 에이전트를 추가 실행했다. 실행 범위는 비파괴 산출물 생성이다. 새 학습, 이미지/라벨 수정, blur, EXIF strip 복사본 생성, `datasets/walksafe_kr_v3` 생성은 하지 않았다.

### 15.1 v3 master manifest

- 산출물: `data_sources/manifests/walksafe_kr_v3_master_manifest_2026-05-20.csv`
- rows: 196
- row_id 중복: 0
- original_image_path 빈 값: 0
- final_decision 빈 값: 0
- final_decision: 전부 `hold_not_train_ready`

해석: PM 기준 master manifest는 생성됐지만 즉시 학습 가능 행은 0이다. `reviewer`, `reviewed_at`은 실제 human final review가 없어 `pending`이다.

### 15.2 privacy gate 산출물

산출물:

- `data_sources/manifests/walksafe_kr_v3_privacy_policy_2026-05-20.md`
- `data_sources/manifests/walksafe_kr_v3_privacy_audit_final_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_exif_strip_log_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_face_review_final_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_plate_review_final_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_filename_sanitize_map_2026-05-20.csv`

결과:

| 항목 | 값 |
| --- | ---: |
| privacy audit rows | 196 |
| 초기 보수 판정 `privacy_hold` | 196 |
| EXIF present | 75 |
| face candidate positive rows | 60 |
| plate check | 196행 `unavailable_not_run` |
| sanitize filename 중복 | 0 |

해석: 이 표는 초기 보수 판정이다. 이후 sanitized copy 생성/EXIF strip이 수행됐고, 사용자 수동 검수 결과 privacy_hold는 민감정보 없음으로 해제됐으며 최종 블러는 불필요하다.

### 15.3 label gate 산출물

산출물:

- `data_sources/manifests/walksafe_kr_v3_label_policy_2026-05-20.md`
- `data_sources/manifests/walksafe_kr_v3_label_decision_final_2026-05-20.csv`

`final_label_status` 분포:

| status | rows |
| --- | ---: |
| `blocked` | 88 |
| `hold` | 59 |
| `candidate_after_privacy_split` | 37 |
| `relabel_required` | 12 |

검증:

- rows: 196
- `use_pred_box_as_label=no`: 196행
- 실제 label txt 수정: 하지 않음

해석: label gate도 닫히지 않았다. `candidate_after_privacy_split` 37행도 privacy/split gate 통과 전 학습 투입 가능 상태가 아니다.

### 15.4 split / holdout gate 산출물

산출물:

- `data_sources/manifests/walksafe_kr_v3_split_policy_2026-05-20.md`
- `data_sources/manifests/walksafe_kr_v3_split_groups_final_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_split_assignment_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_holdout_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_test_manifest_2026-05-20.csv`

결과:

| 항목 | 값 |
| --- | ---: |
| split_groups rows | 196 |
| split_assignment rows | 196 |
| split_group_id 빈 값 | 0 |
| final test 배정 | 0 |
| holdout 배정 | 0 |
| holdout/test 평가 이미지 | 0 |

`assignment_status` 분포:

| assignment_status | rows |
| --- | ---: |
| `hold_for_manual_review` | 97 |
| `excluded_from_eval_until_independent_holdout` | 59 |
| `hard_negative_pool` | 37 |
| `train_candidate_pool` | 3 |

해석: split/holdout gate도 닫히지 않았다. split group은 자동 초안이며 독립 holdout/test 데이터가 없다.

### 15.5 최종 현재 상태

현재 모델 학습 상태를 한 문장으로 말하면 다음과 같다.

> v2 baseline은 동결 가능하지만, v3는 master/privacy/label/split manifest까지 준비된 “학습 전 gate 정리 단계”이며, 즉시 학습 가능 데이터는 0행이다.

다음 AI가 이어받는다면 먼저 해야 할 일은 학습이 아니라 아래다.

1. bbox relabel 12행 실제 보정.
2. 사용자 검수로 파손 없음이 확인된 hard-negative 빈 라벨 후보를 split/manifest에 반영.
3. min-box 9행과 small/far 50행은 v3.0 제외 유지, duplicate/extra 47행 수동 결정.
4. split_group 자동 초안을 사람 검수로 확정.
5. 독립 holdout/test 원본 확보.
6. 그 다음 `datasets/walksafe_kr_v3` 생성.

## 16. 2026-05-20 사용자 정책 승인 후 privacy sanitize staging 실행

사용자가 다음 정책을 승인했다.

```text
1. 학습용 복사본 생성, EXIF 제거, 파일명 sanitize 진행
2. 자동 얼굴/번호판 후보는 검토했으나, 사용자 수동 검수 결과 최종 블러 불필요
3. 차량번호 자동 검출 진행
4. small/far 50개는 v3.0 제외 + 별도 실험군
5. min-box 9개는 v3.0 제외
6. empty label candidate 37개는 사용자 검수 결과 파손 점자블럭 없음, hard-negative 빈 라벨 후보
7. 독립 holdout/test는 현재 없음
```

### 16.1 실행한 작업

원본 이미지는 수정하지 않고, privacy gate용 sanitized staging image pool을 생성했다.
최종 v3 dataset(`datasets/walksafe_kr_v3`)은 아직 만들지 않았다.

산출물:

- `datasets/walksafe_kr_v3_privacy_sanitized_20260520/images/`
- `data_sources/manifests/walksafe_kr_v3_sanitized_unique_images_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_sanitized_image_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_privacy_audit_final_post_sanitize_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_exif_strip_log_post_sanitize_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_face_review_final_auto_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_plate_review_final_auto_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_filename_sanitize_map_post_sanitize_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_master_manifest_post_sanitize_2026-05-20.csv`
- `docs/execution/2026-05-20_model_v3_privacy_sanitize_execution.md`

### 16.2 privacy sanitize 결과

| 항목 | 값 |
| --- | ---: |
| source rows | 196 |
| unique source path strings | 126 |
| sanitized image copies | 126 |
| sanitized EXIF present | 0 |
| 최종 blur count | 0 |
| privacy hold remaining | 0 |

row 기준 privacy decision:

| decision | rows |
| --- | ---: |
| `privacy_pass_user_review_no_sensitive_no_blur` | 196 |

unique image 기준 privacy decision:

| decision | rows |
| --- | ---: |
| `privacy_pass_user_review_no_sensitive_no_blur` | 126 |

주의:

- 자동 얼굴/번호판 detector 후보 count는 감사 추적용으로 manifest에 남아 있다.
- 최종 판단은 사용자 수동 검수 결과다: 민감정보 없음, 블러 불필요.
- 최종 외부 공개나 배포 전 별도 기준이 필요하면 추가 검수 정책을 새로 정해야 한다.

## 17. 2026-05-20 사용자 정책 반영 후 v3.0 readiness

산출물:

- `data_sources/manifests/walksafe_kr_v3_label_decision_final_post_policy_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_readiness_after_user_policy_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_readiness_after_user_policy_summary_2026-05-20.json`
- `datasets/walksafe_kr_v3_privacy_sanitized_20260520/BUILD_SUMMARY.md`
- `docs/execution/2026-05-20_model_v3_user_policy_readiness.md`

label policy decision:

| decision | rows |
| --- | ---: |
| `no_damaged_tactile_block_confirmed_hard_negative` | 55 |
| `manual_visual_review_required` | 70 |
| `manual_bbox_relabel_required` | 12 |
| `exclude_v3_0_small_far_policy` | 50 |
| `exclude_v3_0_min_box_policy` | 9 |

최종 readiness:

| status | rows |
| --- | ---: |
| `staging_ready_pending_holdout` | 55 |
| `not_train_ready` | 141 |

현재 결론:

> privacy blocker는 사용자 수동 검수로 해제됐고 hard-negative 55행은 빈 라벨 staging 후보가 됐다. 다만 독립 holdout/test가 없으므로 공식 full-train/eval ready row는 0개다.

사용자 정성 판단으로 파손 탐지는 90% 이상으로 보이나, 이는 spot-check이며 독립 holdout/test 공식 metric이 아니다.

남은 blocker:

1. bbox relabel 12행 실제 수정.
2. duplicate/positive/manual review 대상 70행 결정.
3. 사용자 검수로 파손 없음이 확인된 hard-negative 빈 라벨 후보 55행을 split/manifest에 반영.
4. small/far 50행과 min-box 9행은 v3.0 제외 유지.
5. 독립 holdout/test 데이터 확보.

## 사용자 추가 승인 반영 - bbox 12행 / holdout 후보

- 3번 bbox/파손 검토는 사용자 정성 기준 90%+로 수용했다.
- `manual_bbox_relabel_required`였던 12행은 `bbox_user_review_pass_existing_gt_label`로 전환했다.
- `pred_box_xywhn`는 여전히 라벨로 복사하지 않고, 기존 source GT label을 사용한다.
- v3 train staging 후보는 46행이다: hard-negative 34행 + positive existing GT 12행.
- `VL1+VS1 hard-negative 200`을 holdout 후보로 보존하기 위해, 해당 subset에서 온 hard-negative review 21행은 train staging에서 제외하고 `holdout_reserved_not_train`으로 둔다.
- holdout 후보는 `VL2+VS2` positive 2,082장/5,326 boxes와 `VL1+VS1` negative 200장/0 boxes다.
- 이 holdout 후보는 이미 v2 외부검증/오류분석에 사용되어 최종 blind test로는 약하므로, v3 학습/threshold tuning에 섞지 않는 조건으로만 독립 holdout 후보로 사용한다.


## 18. 2026-05-20 v3 smoke materialization and holdout-candidate eval

사용자 승인 후 v3 staging을 실제 YOLO dataset으로 materialize했다.

산출물:

- `datasets/walksafe_kr_v3/`
- `datasets/walksafe_kr_v3_holdout_candidate/`
- `data_sources/scripts/validate_yolo_dataset.py`
- `data_sources/manifests/walksafe_kr_v3_materialized_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_materialized_summary_2026-05-20.json`
- `data_sources/manifests/walksafe_kr_v3_holdout_materialized_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_holdout_materialized_summary_2026-05-20.json`
- `data_sources/manifests/walksafe_kr_v3_smoke_train_summary_2026-05-20.json`
- `data_sources/manifests/walksafe_kr_v3_holdout_eval_summary_2026-05-20.json`
- `docs/execution/2026-05-20_model_v3_validator.md`
- `docs/execution/2026-05-20_model_v3_holdout_candidate.md`
- `docs/execution/2026-05-20_model_v3_smoke_training.md`
- `docs/execution/2026-05-20_model_v3_holdout_eval.md`

Materialized v3 dataset:

| 항목 | 값 |
| --- | ---: |
| unique images | 35 |
| train images | 28 |
| val images | 7 |
| positive boxes | 36 |
| empty labels | 26 |

1 epoch smoke training은 성공했다. 단, smoke metric은 공식 성능이 아니다.

Holdout 후보셋에서 v2 baseline과 v3 smoke를 같은 조건으로 비교한 결과:

| model | precision | recall | mAP50 | mAP50-95 |
| --- | ---: | ---: | ---: | ---: |
| v2 baseline | 0.743 | 0.605 | 0.676 | 0.499 |
| v3 smoke | 0.755 | 0.590 | 0.671 | 0.494 |
| delta v3-v2 | +0.012 | -0.015 | -0.005 | -0.005 |

해석:

- v3 smoke는 precision만 소폭 높고 recall/mAP는 v2보다 소폭 낮다.
- v3 smoke는 35장 unique staging으로 1 epoch만 학습했으므로 개선/퇴보 결론으로 쓰지 않는다.
- holdout 후보는 v2 외부검증/오류분석에 이미 쓰였기 때문에 최종 blind test로는 약하다. full v3 학습 후 가능하면 새 blind holdout/test에서 재평가해야 한다.
- Ultralytics eval 중 duplicate label 1개가 제거되어 metric instance는 5,325개이고, manifest label line count는 5,326개다.
- eval logs에 `corrupt JPEG restored and saved` warning이 157회 기록됐다. holdout dataset은 symlink 기반이라 원본 target image가 수정됐을 가능성이 있다.


## 19. 2026-05-20 v3 manual review pack for additional positives

사용자가 2번 경로(70개 manual review / additional positive 정리 후 학습)를 선택했다.

자동으로 라벨을 확정하지 않고, 사용자 검수용 visual review pack을 만들었다.

산출물:

- `datasets/walksafe_kr_v3_manual_review_20260520/BUILD_SUMMARY.md`
- `datasets/walksafe_kr_v3_manual_review_20260520/images/overlays/` local ignored images
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crops/` local ignored images
- `datasets/walksafe_kr_v3_manual_review_20260520/images/contact_sheets/` local ignored images
- `datasets/walksafe_kr_v3_manual_review_20260520/images/crop_contact_sheets/` local ignored images
- `data_sources/manifests/walksafe_kr_v3_manual_review_70_manifest_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_template_2026-05-20.csv`
- `data_sources/manifests/walksafe_kr_v3_manual_review_70_summary_2026-05-20.json`
- `data_sources/scripts/build_v3_manual_review_pack.py`
- `docs/execution/2026-05-20_model_v3_manual_review_pack.md`

대상 분포:

| bucket | rows |
| --- | ---: |
| `false_positive_extra_box` | 47 |
| `missed_defect` | 20 |
| `small_or_far` | 3 |
| total | 70 |

unique image는 47장이다.

검수자는 `data_sources/manifests/walksafe_kr_v3_manual_review_70_decision_template_2026-05-20.csv`의 `review_decision`에 아래 중 하나를 채워야 한다.

- `accept_existing_gt_positive`
- `needs_bbox_relabel`
- `hard_negative_empty_label`
- `exclude_unclear_or_policy`

주의:

- red box는 triage prediction이므로 그대로 label로 복사하지 않는다.
- green box는 source label, yellow box는 review queue candidate GT다.
- 이 decision template이 채워지기 전에는 70개를 v3 train staging에 반영하지 않는다.
