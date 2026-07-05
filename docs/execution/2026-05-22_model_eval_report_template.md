# 2026-05-22 Model Evaluation Report Template

대상 run: `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521`

이 문서는 YOLO26s 학습 완료 직후 보고서에 넣을 항목과 CPU-only 요약 스크립트 사용법을 정리한다. 요약 스크립트는 `results.csv`와 파일 메타데이터만 읽으며 추론/평가/GPU 작업을 실행하지 않는다.

## 요약 스크립트 사용법

stdout으로만 확인:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py
```

JSON/Markdown 파일로 저장:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py \
  --out-json runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/summary_20260522.json \
  --out-md docs/execution/2026-05-22_walksafe_tactile3_yolo26s_summary.md
```

다른 `results.csv`를 지정:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py \
  --results-csv runs/detect/<run_name>/results.csv
```

스크립트가 계산/기록하는 항목:

- completed epoch 수
- last epoch metrics
- best `metrics/mAP50-95(B)` epoch metrics
- best `metrics/mAP50(B)` epoch metrics
- `weights/best.pt`, `weights/last.pt` 존재 여부, mtime, size
- stage1 test / stage2 train / stage2 test 결과 디렉터리 존재 여부
  - 이 디렉터리들은 후속 작업 전에는 `missing_ok`가 정상이다.

## 보고서에 넣을 항목

### 1. Run 메타데이터

- Run name:
- 실행 일시:
- 데이터셋 yaml:
- 초기 모델/체크포인트:
- 주요 학습 설정: epochs, imgsz, batch, optimizer, lr0/lrf, augmentation, patience
- 실행 스크립트:
- 코드 revision/branch(확인 가능한 경우):

### 2. 학습 완료 상태

- completed epochs:
- 종료 상태: 정상 완료 / early stop / 중단 후 resume / 확인 필요
- `results.csv` 경로:
- 특이사항:

### 3. 핵심 지표

요약 스크립트의 `Key epoch metrics` 표를 붙인다.

- last epoch:
- best mAP50-95 epoch:
- best mAP50 epoch:
- precision/recall 변화 해석:
- val loss 변화 해석:

### 4. 체크포인트

- `best.pt`: 존재 여부, mtime, size
- `last.pt`: 존재 여부, mtime, size
- 배포/후속 평가 후보: `best.pt` 또는 별도 지정 필요

### 5. Stage 결과

- stage1 test 결과 디렉터리:
- stage2 train 결과 디렉터리:
- stage2 test 결과 디렉터리:
- 아직 없으면 `missing_ok`로 기록하고, 후속 평가 완료 후 별도 결과를 추가한다.

### 6. 결론/판정

- 현재 run을 다음 단계로 넘길지 여부:
- 기준 대비 개선/악화:
- 후속 작업:
  - stage1 test 결과 확인
  - stage2 fine-tune 결과 확인
  - stage2 test 결과 확인
  - 필요 시 독립 holdout/test 평가로 최종 성능 확정

### 7. 제한 사항

- 이 요약은 `results.csv` 기반 학습 로그 요약이다.
- stage test/holdout 결과가 없으면 공식 최종 성능으로 단정하지 않는다.
- 스크립트는 GPU를 사용하지 않고 모델 파일을 로드하지 않는다.
