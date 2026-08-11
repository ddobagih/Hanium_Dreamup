# 2026-05-20 Model failure sampling 200

범위: v2 `best.pt`로 `walksafe_kr_v2` test split 200장 bounded streaming failure sampling을 실행했다. 새 학습, full 2,347장 sampling, VL1+VS1 전체 inference, 이미지 저장은 수행하지 않았다.

## Gate

```text
Filesystem      Size  Used Avail Use% Mounted on
/dev/nvme0n1p2  915G  853G   16G  99% /
/dev/nvme0n1p2  915G  853G   16G  99% /
```

디스크 가용은 `16G`로 실행 기준 `10G` 이상이어서 bounded 200장 sampling만 진행했다.

## 실행 명령

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

결과: PASS.

## 요약

| 항목 | 값 |
| --- | ---: |
| full split images | 2347 |
| sample images | 200 |
| positive / negative sample images | 100 / 100 |
| GT boxes | 334 |
| predictions | 259 |
| matched GT boxes | 190 |
| missed GT boxes | 144 |
| negative images with prediction | 12 |
| candidate rows written | 135 |
| generated image files | 0 |

## bucket별 후보

| bucket | events | rows written |
| --- | ---: | ---: |
| `false_positive_extra_box` | 47 | 47 |
| `false_positive_normal_tactile` | 18 | 18 |
| `missed_defect` | 20 | 20 |
| `small_or_far` | 124 | 50 |


`small_or_far`는 event `124`건 중 bucket limit 때문에 `50`행만 기록됐다.

## 산출물

- `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/failure_candidates.csv`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/summary.json`
- `runs/failure_sampling/walksafe_kr_v2_test_stream_200_20260520/checkpoint.json`

위 산출물은 ignored `runs/` 아래에 있으며 Git 커밋 대상이 아니다.

## 검증

- sampling command exit code: PASS
- output files: `failure_candidates.csv`, `summary.json`, `checkpoint.json` 존재
- generated image files count: `0`

## 판단

- 80장 smoke보다 넓은 200장에서도 streaming sampler는 동작했고 이미지 파일을 만들지 않았다.
- `small_or_far`와 missed GT가 많아 min-box/작은 결함 정책을 확정하기 전에는 v3 학습 데이터로 바로 편입하지 않는다.
- full 2,347장 sampling은 아직 실행하지 않았다. 200장 결과 검수와 디스크/RSS/시간 gate를 본 뒤 별도 판단한다.

## 미완료 / 제한

- 새 학습은 하지 않았다.
- full test split 2,347장 sampling은 하지 않았다.
- contact sheet나 prediction image는 만들지 않았다.
- 후보 CSV는 자동 추출 결과이며 privacy/location audit과 수동 시각 검수가 필요하다.
