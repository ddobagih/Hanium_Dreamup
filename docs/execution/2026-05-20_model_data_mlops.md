# 2026-05-20 Model/Data/MLOps parallel execution

범위: 사용자 요청에 따라 모델 학습 준비 작업을 병렬로 실행했다. 새 학습, full test split sampling, VL1+VS1 전체 inference, 대형 다운로드, 이미지/라벨 수정은 수행하지 않았다.

## 공통 gate

| 항목 | 결과 |
| --- | --- |
| 작업트리 | 기존 `daylog/2026-05-19.md`, `plans/.work/2026-05-19/daily/*.md`, `plans/daily/2026-05-20.md` 미커밋/미추적 변경이 있어 덮어쓰지 않음 |
| 디스크 | `/home/ddobagi/Code/hanium-dreamup`와 `/home/ddobagi/Downloads` 가용 `16G`, 사용률 `99%` |
| `best.pt` sha256 | `02a6be87626e9ba00bb72715d45d8c27d06103d851882a08f404260e453d8e94` |
| `best.onnx` sha256 | `c21f47013ad340761a2743bc20ba36da8aa4110c40a16bb0ddf7b6913858efe7` |
| Git 대형 산출물 추적 | `.pt`, `.onnx`, `runs/**`, dataset images/labels 추적 없음. `.gitkeep`만 확인 |

## 병렬 실행 구성

| 담당 | 작업 | 산출물 | 결과 |
| --- | --- | --- | --- |
| Agent A | v3 후보/정책 감사 | `docs/execution/2026-05-20_model_v3_candidate_audit.md` | PASS |
| Agent B | min-box/bbox 검토안 | `data_sources/manifests/walksafe_kr_v3_minbox_bbox_review_2026-05-20.md` | PASS, 학습 gate는 PENDING/BLOCKED |
| Agent C | privacy/location audit와 split leakage 정책 | `data_sources/manifests/walksafe_kr_v3_privacy_split_audit_2026-05-20.md` | PASS, source-level audit은 미완료 |
| Parent | 200장 failure sampling | `docs/execution/2026-05-20_model_failure_sampling_200.md`, ignored `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/**` | PASS |
| Parent | class `1..3` 데이터 계획 | `data_sources/manifests/walksafe_kr_v4_class123_data_plan_2026-05-20.md` | PASS, 데이터 원본 미보유 |

## v3 후보 감사 결과

입력:

- `data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv`
- `data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json`

| 항목 | 값 | 결과 |
| --- | ---: | --- |
| CSV rows | 61 | PASS |
| summary `rows_total` | 61 | PASS |
| source manifest count | 40 + 21 | PASS |
| policy count 합계 | 61 | PASS |

policy별 count:

| policy_decision | rows |
| --- | ---: |
| `include_as_hard_negative` | 33 |
| `include_after_box_review` | 12 |
| `hold_until_min_box_policy_review` | 9 |
| `include_with_small_object_augmentation` | 3 |
| `include_as_hard_negative_and_prioritize_threshold_augmentation_review` | 4 |

판정:

- policy상 train 후보는 40행이지만 source-level privacy/location audit 전 실제 학습 투입은 금지다.
- bbox 수동 보정 필요 12행과 min-box hold 9행은 아직 학습 gate를 막는다.

## min-box / bbox 검토 결과

대상 row count:

| 대상 | rows | 처리안 |
| --- | ---: | --- |
| min-box 보류 | 9 | 현재는 `keep_hold` 권장. min-box 정책 확정 전 포함 금지 |
| bbox 보정 대상 | 12 | `manual_relabel` 권장. 라벨 파일은 수정하지 않음 |
| small-object 후보 | 3 | `small_object_include` 가능하나 bbox 최종 확인 필요 |

중요 판단:

- min-box 9행은 실제 이미지 재확인과 최소 결함 크기/경보 가치 정책 전까지 학습에 넣지 않는다.
- bbox 12행은 true damage 후보이나 예측이 긴 점자블록 strip으로 퍼지는 경향이 있어, 원본 라벨을 바로 쓰기보다 수동 보정 후 포함한다.

## privacy/location audit와 split leakage 정책

확인된 값:

| 항목 | 값 | rows |
| --- | --- | ---: |
| privacy_status | contact sheet 기준 얼굴/차량번호 없음, source audit 전 local-only | 40 |
| privacy_status | review sheet 기준 얼굴/차량번호 없음, source audit 전 local-only | 21 |
| split_policy | `do_not_cross_split_with_same_location_or_sequence` | 61 |

정책:

- source-level 원본 이미지 기준으로 얼굴, 차량번호, 신체 일부, 민감 위치, 위치 식별 배경, EXIF/메타데이터를 확인한다.
- 같은 장소/연속 프레임/파일 prefix/촬영일/시퀀스는 하나의 `leakage_group_key`로 묶고 train/val/test에 섞지 않는다.
- v2 test failure 후보를 v3 train에 넣으면 기존 v2 test metric은 v3 평가 근거로 재사용하지 않는다.

## 200장 failure sampling 결과

명령:

```bash
.venv/bin/python model/sample_yolo_failures.py \
  --model runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  --data datasets/walksafe_kr_v2/data.yaml \
  --split test \
  --output-dir runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520 \
  --max-images 200 \
  --seed 20260520 \
  --device cpu \
  --checkpoint-every 25 \
  --overwrite
```

결과:

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

Bucket:

| bucket | events | rows written |
| --- | ---: | ---: |
| `false_positive_extra_box` | 47 | 47 |
| `false_positive_normal_tactile` | 18 | 18 |
| `missed_defect` | 20 | 20 |
| `small_or_far` | 124 | 50 |

판정:

- 80장 smoke보다 넓은 200장에서도 sampler는 동작했고 이미지 파일을 만들지 않았다.
- `small_or_far`가 많아 min-box/작은 결함 정책 확정 전 v3 학습에 바로 넣지 않는다.
- full 2,347장 sampling은 아직 하지 않았다.

## class `1..3` / v4 데이터 계획

산출물: `data_sources/manifests/walksafe_kr_v4_class123_data_plan_2026-05-20.md`

현재 repo와 `/home/ddobagi/Downloads` 검색 기준 AI Hub 159 `Average_stature/out`의 `School`, `Building_area`, `Bridge`, `Park`, `Residential_area`, `Market` zip 후보는 확인되지 않았다.

1차 목표:

| class | positive 목표 | negative 목표 | 상태 |
| --- | ---: | ---: | --- |
| `parked_kickboard_bicycle` | 500 | 300 | 원본 확보 필요 |
| `construction_obstacle` | 500 | 300 | 원본 확보 필요 |
| `pothole` | 500 | 300 | 원본 확보 필요 |
| 공통 hard negative | 600 | - | 원본 확보 필요 |

class `1..3` 한국 GT가 준비되기 전까지 4-class metric은 산출하지 않는다.

## 현재 v3 학습 gate

| gate | 상태 | 이유 |
| --- | --- | --- |
| candidate index 검산 | PASS | 61행과 summary count 일치 |
| min-box 정책 | BLOCKED | 9행 `keep_hold` 권장, 정책 미확정 |
| bbox 보정 | PENDING | 12행 `manual_relabel` 필요 |
| privacy/location audit | BLOCKED | sheet 수준 검수만 완료, source-level audit 미완료 |
| split leakage 정책 | PENDING | 정책 초안 작성, 실제 `leakage_group_key` manifest 필요 |
| 200장 sampler | PASS | 이미지 저장 없이 135행 후보 생성 |
| full sampling | PENDING | 2,347장 전체 미실행 |
| class `1..3` GT | BLOCKED | 원본/라벨 미확보 |
| 새 v3 학습 | BLOCKED | 위 gate 해소 전 실행 금지 |

## 다음 실행 순서

1. min-box 9행의 실제 이미지 재확인 후 `exclude`/`small_object_include`/`manual_relabel`/`hold`를 확정한다.
2. bbox 12행을 원본 이미지 기준으로 수동 보정한다.
3. 61행 전체에 source-level privacy/location audit 결과와 `leakage_group_key`를 붙인 v3 split manifest를 만든다.
4. 200장 sampling CSV를 수동 triage해 v3 후보로 편입할지 결정한다.
5. 위 gate가 닫히면 `datasets/walksafe_kr_v3` 생성 스크립트 또는 절차를 별도 작업으로 만든다.
6. v3 dataset 검증 통과 후에만 `walksafe_kr_tactile_v3_smoke` 학습을 검토한다.

## 검증

- `git status --short --branch --untracked-files=all`
- `df -h /home/ddobagi/Code/hanium-dreamup /home/ddobagi/Downloads`
- `sha256sum runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt runs/detect/walksafe_kr_tactile_v2_full/weights/best.onnx`
- `git ls-files '*.pt' '*.onnx' 'runs/**' 'datasets/**/images/**' 'datasets/**/labels/**'`
- Python CSV/JSON count checks for candidate index
- `model/sample_yolo_failures.py` 200장 bounded run
- generated image files count: `0`
- AI Hub 159 우선 zip 후보 검색
- `git diff --check`

## 미완료 / 제한

- 새 학습은 하지 않았다.
- 이미지/라벨 원본은 수정하지 않았다.
- full test split sampling은 하지 않았다.
- VL1+VS1 전체 hard-negative inference는 하지 않았다.
- browser/ONNX Runtime Web latency는 실행하지 않았다.
- class `1..3` 한국 GT가 없어 4-class metric은 산출하지 않았다.

---

## 추가 병렬 실행: v3 review queue / split / build dry-run 계획

사용자 요청에 따라 v3 학습 gate를 더 세분화하기 위해 추가 병렬 작업을 실행했다. 이 단계에서도 새 학습, 이미지/라벨 복사, `datasets/walksafe_kr_v3` 생성은 하지 않았다.

### 추가 병렬 작업 구성

| 담당 | 작업 | 산출물 | 결과 |
| --- | --- | --- | --- |
| Agent A | v3 candidate 61행 + 200장 sampling 후보 135행 통합 review queue 생성 | `data_sources/manifests/walksafe_kr_v3_review_queue_2026-05-20.csv`, `data_sources/manifests/walksafe_kr_v3_review_queue_summary_2026-05-20.json` | PASS |
| Agent B | v3 61행 파일명 기반 leakage group/split 초안 생성 | `data_sources/manifests/walksafe_kr_v3_leakage_groups_2026-05-20.csv`, `data_sources/manifests/walksafe_kr_v3_leakage_split_plan_2026-05-20.md` | PASS |
| Agent C | `datasets/walksafe_kr_v3` 생성 전 dry-run/build 계획 문서화 | `docs/execution/2026-05-20_model_v3_dataset_build_plan.md` | PASS |
| Agent D | 200장 sampling 후보 135행 triage 요약 | `docs/execution/2026-05-20_model_sampling_200_triage.md` | PASS |

### 통합 review queue

입력:

- v3 candidate index: 61행
- 200장 sampling failure candidates: 135행

출력:

| 항목 | 값 |
| --- | ---: |
| total review rows | 196 |
| `v3_candidate_index` rows | 61 |
| `sampling_200` rows | 135 |

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

주의: `include_candidate`는 policy상 후보일 뿐이며, privacy/location audit과 split group 확인 전 실제 학습 투입 가능 상태가 아니다.

### leakage/split 초안

v3 candidate index 61행에 대해 파일명 기반 `leakage_group_key` 초안을 만들었다.

| 항목 | 값 |
| --- | ---: |
| input rows | 61 |
| output rows | 61 |
| leakage group count | 46 |
| groups with 2+ candidates | 11 |
| duplicate source image basename kinds | 8 |
| duplicate basename total rows | 16 |

초안 split:

| proposed_v3_split | rows | 의미 |
| --- | ---: | --- |
| `train_candidate` | 40 | v2 test failure 기반 hard example mining 후보. 기존 v2 test metric 재사용 금지 |
| `hard_negative_pool` | 21 | VL1+VS1 hard-negative FP review 후보. 독립 holdout 전 평가 사용 금지 |

주의: 이 group은 파일명 기반 초안이며, 원본 이미지 시각 검토, EXIF/메타데이터, 위치/시퀀스 검토, pHash/embedding near-duplicate 검사를 대체하지 않는다.

### 200장 sampling triage

`runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/failure_candidates.csv` 135행을 bucket/action/privacy 기준으로 요약했다.

| bucket | rows | triage 기준 |
| --- | ---: | --- |
| `false_positive_normal_tactile` | 18 | hard negative 후보. privacy/location audit 후 정상 장면이면 hard negative 편입 검토 |
| `false_positive_extra_box` | 47 | 중복/박스 과다 후보. duplicate box인지 실제 GT 누락인지 수동 확인 필요 |
| `missed_defect` | 20 | positive 추가 또는 bbox 보정 후보 |
| `small_or_far` | 50 | min-box 정책 확정 전 hold |

모든 sampling 후보는 `privacy_review_required=yes`다.

### v3 dataset build dry-run 계획

`docs/execution/2026-05-20_model_v3_dataset_build_plan.md`에 `datasets/walksafe_kr_v3` 생성 전 절차를 정리했다.

핵심 gate:

1. source-level privacy/location audit 완료.
2. split leakage manifest와 `leakage_group_key` 검증.
3. bbox 보정 대상 12행 처리.
4. min-box 보류 9행 처리.
5. hard negative empty label 정책 확정.
6. v2 test metric 오염 방지.
7. dataset 생성 후 `validate_yolo_dataset.py` 통과.
8. `train_yolo.py --dry-run` 통과.
9. smoke 학습 전 사용자 승인.

## 추가 검증

- `data_sources/manifests/walksafe_kr_v3_review_queue_summary_2026-05-20.json` load 및 total rows `196` 확인.
- `data_sources/manifests/walksafe_kr_v3_leakage_groups_2026-05-20.csv` rows `61`, 빈 `leakage_group_key` 0건 확인.
- `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/failure_candidates.csv` rows `135`와 summary `candidate_rows_written=135` 일치 확인.
- `git diff --check` PASS.

## 추가 미완료 / 다음 작업

- review queue 196행의 실제 수동 시각 검수는 아직 남아 있다.
- `include_candidate` 40행도 privacy/location audit과 split group 검증 전 학습 투입 금지다.
- `leakage_group_key`는 파일명 기반 초안이며, 실제 위치/시퀀스/near-duplicate 검증이 필요하다.
- `datasets/walksafe_kr_v3`는 아직 생성하지 않았다.
- smoke 학습은 사용자 승인 전 실행하지 않는다.

---

## 추가 병렬 실행 2: 자동 privacy audit / label decision / near-duplicate / AI brief

사용자 요청에 따라 학습 전 gate 중 자동으로 처리 가능한 부분을 추가 병렬 실행했다. 새 학습, 이미지/라벨 수정, `datasets/walksafe_kr_v3` 생성은 하지 않았다.

### 실행 구성

| 담당 | 작업 | 산출물 | 결과 |
| --- | --- | --- | --- |
| Agent A | review queue 196행 privacy/location 자동 보조 audit | `data_sources/manifests/walksafe_kr_v3_privacy_location_audit_results_2026-05-20.csv`, `data_sources/manifests/walksafe_kr_v3_privacy_location_audit_summary_2026-05-20.json`, `docs/execution/2026-05-20_model_privacy_location_audit.md` | PASS, 최종 인간 audit은 미완료 |
| Agent B | label decision manifest | `data_sources/manifests/walksafe_kr_v3_label_decision_manifest_2026-05-20.csv`, `docs/execution/2026-05-20_model_label_decision_manifest.md` | PASS, 즉시 학습 가능 행 0 |
| Agent C | dHash near-duplicate split audit | `data_sources/manifests/walksafe_kr_v3_near_duplicate_groups_2026-05-20.csv`, `data_sources/manifests/walksafe_kr_v3_near_duplicate_summary_2026-05-20.json`, `docs/execution/2026-05-20_model_near_duplicate_split_audit.md` | PASS, 자동 후보 |
| Agent D | 다른 AI 인계용 학습 브리핑 | `docs/model-data/model_training_ai_brief_2026-05-20.md` | PASS |

### privacy/location 자동 audit 결과

| 항목 | 값 |
| --- | ---: |
| input/output rows | 196 / 196 |
| image exists | 196 / 196 |
| EXIF present | 75 / 196 |
| Haar cascade face candidate >= 1 | 60 / 196 |
| plate check | 196행 `unavailable_not_run` |
| filename scene/date signal | 196 / 196 |
| contact sheet | 6개 |

이 결과는 자동/보조 audit이며 원본 고해상도 인간 검수를 대체하지 않는다.

### label decision 결과

| v3_label_action | rows |
| --- | ---: |
| `hold_small_or_far_policy` | 50 |
| `manual_duplicate_or_box_review_required` | 47 |
| `empty_label_candidate` | 37 |
| `manual_positive_review_required` | 23 |
| `manual_hard_negative_review_required` | 18 |
| `manual_bbox_relabel_required` | 12 |
| `hold_min_box_policy` | 9 |

| final_gate_status | rows |
| --- | ---: |
| `blocked_by_small_or_far_policy` | 50 |
| `blocked_by_manual_duplicate_or_box_review` | 47 |
| `blocked_by_privacy_split_audit` | 40 |
| `blocked_by_manual_positive_review` | 20 |
| `blocked_by_manual_hard_negative_review` | 18 |
| `blocked_by_relabel` | 12 |
| `blocked_by_min_box_policy` | 9 |

즉시 학습 가능 행은 0이다. policy상 후보 40행도 privacy/split gate 전까지 blocked 상태다.

### dHash near-duplicate 결과

| 항목 | 값 |
| --- | ---: |
| input/output rows | 196 / 196 |
| unique hash count | 120 |
| near-duplicate groups | 44 |
| near-duplicate rows | 120 |
| filename group으로 완전히 커버되지 않는 group | 37 |
| `new_near_duplicate_candidate_not_in_filename_group` rows | 106 |

이 결과는 자동 후보이며 final split을 대체하지 않는다.

### AI 인계 문서

`docs/model-data/model_training_ai_brief_2026-05-20.md`에 다른 AI에게 설명할 모델 학습 현황을 정리했다. 주요 내용은 v2 baseline metric, v1/v2 데이터 규모, v3 review queue, privacy/near-duplicate/label decision 현황, class `1..3` 미확보 상태, 다음 작업, 과대해석 금지 문구다.

## 추가 검증

- privacy audit input/output rows 196 확인.
- summary JSON load 확인.
- contact sheet 6개 확인.
- label decision output rows 196 확인.
- near-duplicate output rows 196, hash 빈 값 0 확인.
- `git diff --check` PASS.

## 현재 최종 판단

v3 학습은 아직 실행하면 안 된다. 자동 audit/manifest는 진전이지만, 남은 gate는 다음과 같다.

1. 얼굴 후보 60행/EXIF 75행/위치 신호 196행에 대한 최종 인간 검수.
2. `manual_bbox_relabel_required` 12행 실제 bbox 보정.
3. `hold_min_box_policy` 9행과 `hold_small_or_far_policy` 50행 정책 결정.
4. dHash near-duplicate 후보 120행 수동 확인 및 split manifest 최종화.
5. 독립 v3 holdout/test 확보.

---

## 추가 병렬 실행 3: 첨부 v3 계획표 기반 PM/Privacy/Label/Split gate 산출물

사용자가 첨부한 `walksafe_v3_vision_ai_plan.md`의 D0~D5 범위를 비파괴 방식으로 실행했다. 새 학습, 이미지/라벨 수정, blur, EXIF strip 복사본 생성, `datasets/walksafe_kr_v3` 생성은 하지 않았다.

### 실행 구성

| Agent | 역할 | 산출물 | 결과 |
| --- | --- | --- | --- |
| PM | v3 master manifest 생성 | `walksafe_kr_v3_master_manifest_2026-05-20.csv` | 196행, `hold_not_train_ready` 196행 |
| Privacy | privacy policy/final hold audit/log/map 생성 | privacy policy, privacy audit final, exif/face/plate/sanitize CSV | `privacy_hold` 196행 |
| Label | label policy/final decision 생성 | label policy, label decision final CSV | 즉시 승인 0행 |
| Split/Holdout | split policy/groups/assignment/holdout/test 생성 | split policy, split groups, split assignment, holdout/test manifests | final test/holdout 0행 |

### 핵심 결과

| gate | 결과 | 상태 |
| --- | --- | --- |
| PM master | 196행, row_id 중복 0, final_decision 전부 `hold_not_train_ready` | PASS, 학습은 BLOCKED |
| Privacy | `privacy_hold` 196행, EXIF 75행, 얼굴 후보 60행, plate 미검출 196행 | BLOCKED |
| Label | blocked 88, hold 59, candidate_after_privacy_split 37, relabel_required 12 | BLOCKED |
| Split/Holdout | split_group_id 빈 값 0, final test 0, holdout 0 | BLOCKED |

### 왜 아직 v3 학습을 못 하는가

- privacy final pass 행이 없다. 모든 행이 `privacy_hold`다.
- 실제 label txt가 보정되지 않았다.
- `pred_box_xywhn`는 196행 모두 label로 사용 금지다.
- 독립 holdout/test가 없다.
- split group은 자동 초안이며 final split이 아니다.

## 추가 검증

- master manifest rows 196, row_id duplicate 0, final_decision blank 0.
- privacy/log/map CSV 각 196행, sanitize filename duplicate 0.
- label decision final rows 196, `use_pred_box_as_label=no` 196행.
- split groups/assignment rows 196, split_group_id blank 0, holdout/test 평가 이미지 0.
- `git diff --check` PASS.
