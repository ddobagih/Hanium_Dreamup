# 데이터 소스

이 폴더는 WalkSafe 학습 데이터의 변환 코드, 검수 결정, provenance manifest를 관리합니다. 원본 이미지와 materialize된 대용량 데이터셋은 로컬 전용이며 이 폴더에 복사하거나 Git에 커밋하지 않습니다.

## 현재 기준

- data.yaml: `datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml`
- 구성: 13 classes, train 167,759장, val 28,747장, 독립 test 없음
- 전체 materialize: 196,506장, bbox 607,814개
- 학습/평가 인덱스: `reports/walksafe_best_eval_20260708/training_reference_index_20260708.md`
- 소스별 상세 provenance: `data_sources/manifests/walksafe_aihub_source_usage_20260628.md`
- 현재 데이터 계약: `docs/model-data/walksafe_13class_dataset_source_contract_20260619.md`

실제 포함 소스는 COCO 2017, AIHub 186, AIHub 513, AIHub 189 Surface, AIHub 572 이륜자동차 안전 위험 시설물 승인 전동킥보드, AIHub 189 수동 승인 전동킥보드 7장입니다. 기존 경로와 source key의 `aihub183`은 레거시 내부 별칭입니다. 다운로드 폴더에 파일이 있다는 사실과 현재 학습본에 포함됐다는 사실을 혼동하지 않습니다.

## 폴더 책임

| 경로 | 책임 |
| --- | --- |
| `scripts/` | 데이터 탐색, 빌드, 검수팩 생성·적용, 감사, 검증 CLI |
| `manifests/` | 입력 provenance, split, 변환, 검수 결정과 실행 요약 근거 |
| `manual_reviews/` | 사람이 확정한 검수 CSV. 자동 제안과 구분해서 취급 |
| `labels/` | 사람이 수정해 확정한 소규모 YOLO 라벨 |

스크립트 분류와 안전한 실행 순서는 `data_sources/scripts/README.md`를 봅니다.

## 검증

현재 통합본은 train/val만 선언하므로 configured split을 읽는 검증기를 사용합니다.

```bash
python data_sources/scripts/validate_yolo_dataset.py \
  datasets/walksafe_unified_coco_aihub_13cls_reviewed_aihub183_png_20260627/data.yaml
```

`model/validate_yolo_dataset.py`는 train/val/test를 모두 요구하는 legacy 구조 검증기입니다. 데이터셋을 지정하지 않는 기본 실행은 지원하지 않습니다.

## 과거 데이터셋

`walksafe_v1`, `walksafe_kr_v1`, `walksafe_kr_v2`, `walksafe_kr_v3` 계열은 초기 단일·소수 클래스 실험 기록입니다. 관련 builder와 manifest는 재현 근거로 보존하지만 현재 13-class 학습 입력이나 기본 경로가 아닙니다. 날짜가 붙은 문서는 작성 당시 상태로 읽고, 현재 상태는 위 기준 문서에서 확인합니다.

## 로컬 전용 정책

- `datasets/**/images/**`, `datasets/**/labels/**`, 원본 AIHub zip, review 이미지팩은 Git에 올리지 않습니다.
- source와 target 경로를 항상 명시하고, builder의 `--dry-run` 또는 preflight를 먼저 실행합니다.
- 자동 제안 라벨은 사람 승인 전 학습 데이터로 사용하지 않습니다.
- 기존 materialize 데이터셋을 덮어쓰기 전에 manifest와 백업 경로를 확인합니다.
