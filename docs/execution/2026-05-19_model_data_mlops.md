# 2026-05-19 Model/Data/MLOps catch-up

범위: Vision Model/Data/MLOps lane의 2026-05-19 r1 미완료 항목만 수행했다. 새 학습, 전체 hard-negative inference, full test split 대형 sampling, Git commit/push, daylog 작성은 하지 않았다.

## 기준 문서

- `plans/catchup/2026-05-19.md`
- `plans/daily/2026-05-18.md`
- `plans/.work/2026-05-19/catchup/vision-model-data-mlops.md`
- `daylog/2026-05-18.md`
- `docs/execution/2026-05-18_model_data_mlops.md`
- `README.md`
- `model/README.md`
- `data_sources/README.md`

저장소 내부 `AGENTS.md`는 없었고 사용자 제공 지침을 적용했다.

## Gate 확인

| 항목 | 결과 |
| --- | --- |
| 작업트리 | 기존 `daylog/2026-05-18.md`, `plans/**`, `product/**` 변경/미추적 파일이 있어 건드리지 않음 |
| 디스크 | `/home/ddobagi/Code/hanium-dreamup` 가용 `16G`, 사용률 `99%` |
| test split | `datasets/walksafe_kr_v2/images/test` 2,347장, `labels/test` 2,347개 확인 |
| AI Hub 159/직접 촬영 파일 | repo와 `/home/ddobagi/Downloads` 검색 기준 보유 파일 확인 안 됨 |

## Streaming sampler smoke

`model/sample_yolo_failures.py`를 추가하고 v2 test split 80장 balanced smoke를 실행했다.

명령:

```bash
.venv/bin/python model/sample_yolo_failures.py \
  --model runs/detect/walksafe_kr_tactile_v2_full/weights/best.pt \
  --data datasets/walksafe_kr_v2/data.yaml \
  --split test \
  --output-dir runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519 \
  --max-images 80 \
  --seed 20260519 \
  --device cpu \
  --checkpoint-every 10 \
  --overwrite
```

결과:

| 항목 | 값 |
| --- | ---: |
| sample images | 80 |
| positive / negative | 40 / 40 |
| GT boxes | 129 |
| predictions | 119 |
| matched GT | 79 |
| missed GT | 50 |
| negative images with prediction | 4 |
| candidate rows | 85 |
| generated image files | 0 |

Bucket count:

| bucket | rows |
| --- | ---: |
| `false_positive_extra_box` | 28 |
| `false_positive_normal_tactile` | 7 |
| `missed_defect` | 8 |
| `small_or_far` | 42 |

로컬 산출물은 ignored `runs/` 아래에만 있다.

- `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519/failure_candidates.csv`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519/checkpoint.json`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519/summary.json`

## v3 후보 통합과 정책

5/18 후보 manifest 2개를 통합해 61행 index를 만들었다.

- `data_sources/manifests/walksafe_kr_v3_candidate_index_2026-05-19.csv`
- `data_sources/manifests/walksafe_kr_v3_candidate_index_summary_2026-05-19.json`
- `data_sources/manifests/walksafe_kr_v3_policy_2026-05-19.md`

| policy_decision | rows |
| --- | ---: |
| `include_as_hard_negative` | 33 |
| `include_after_box_review` | 12 |
| `hold_until_min_box_policy_review` | 9 |
| `include_with_small_object_augmentation` | 3 |
| `include_as_hard_negative_and_prioritize_threshold_augmentation_review` | 4 |

학습 gate: min-box 보류 9행과 개인정보/위치정보 source-level audit이 끝나기 전에는 v3 train을 시작하지 않는다.

## 문서 보정

- `model/README.md`: streaming sampler 사용법 추가.
- `data_sources/manifests/korean_dataset_candidates.md`: AI Hub 159/직접 촬영 보유 파일 없음과 소형 subset 우선순위 기록.
- `docs/model_training_status.md`: VL2+VS2 class `0` 외부 검증 완료와 80장 streaming smoke 완료를 반영.
- `docs/model_v2_status.md`: VL2+VS2 stale 문구를 보정하고 v3 후보 61행/min-box 보류를 기록.
- `docs/current_status.md`: 이미 완료된 v2 test/VL2+VS2를 남은 우선순위에서 제거하고 현재 model next step으로 교체.

## 검증

- `.venv/bin/python -m py_compile model/sample_yolo_failures.py`: PASS
- `.venv/bin/python model/sample_yolo_failures.py ... --max-images 80`: PASS
- `find runs/failure_sampling/walksafe_kr_v2_test_stream_smoke_20260519 -type f \( -name '*.jpg' -o -name '*.jpeg' -o -name '*.png' -o -name '*.webp' \) | wc -l`: `0`
- v3 candidate index summary 생성: 61행, source manifest 40행+21행

## 미완료 / 제한

- 전체 test split 2,347장 sampling은 실행하지 않았다. `/` 사용률 99%이고 80장 smoke로 script 동작만 확인했다.
- VL1+VS1 전체 1,038장 hard-negative inference는 실행하지 않았다.
- browser/ONNX Runtime Web latency는 실행하지 않았다. 브라우저/PWA 런타임 측정 환경이 없어 기존 backend CPU latency와 구분해 보류했다.
- 새 학습은 하지 않았다.
- class `1..3` 한국 GT가 없어 4-class metric은 산출하지 않았다.
- AI Hub 159/직접 촬영 후보 원본 파일은 확인되지 않았다.
