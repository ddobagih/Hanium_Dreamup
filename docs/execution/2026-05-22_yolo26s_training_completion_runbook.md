# YOLO26s 학습 완료 후 Runbook (2026-05-22)

## 범위와 금지 사항

- 대상 프로젝트: `/home/ddobagi/Code/hanium-dreamup`
- 대상 데이터셋: `datasets/walksafe_kr_tactile_3class_20260521/data.yaml`
- 현재 YOLO26s 파이프라인이 진행 중이면 **추가 GPU 작업, 추론, 평가, validation 실행 금지**.
- 이 문서의 상태 확인/요약 명령은 기존 로그와 `results.csv`/파일 메타데이터를 읽는 용도다.
- 최종 `test` split 결과로 threshold/model을 반복 tuning하지 않는다. 최종 test는 1회성 판정용으로 남긴다.

## 확인한 기존 스크립트

| 파일 | 역할 | 주의 |
| --- | --- | --- |
| `scripts/run_walksafe_tactile3_yolo26s_20260521.sh` | 최초 전체 파이프라인 실행 | train/val을 실제 실행하므로 현재 별도 실행 금지 |
| `scripts/resume_walksafe_tactile3_yolo26s_20260522.sh` | Stage1 `last.pt`에서 재개 후 나머지 파이프라인 진행 | train/val을 실제 실행하므로 중단 확인 전 실행 금지 |
| `scripts/check_walksafe_tactile3_training_status_20260522.sh` | process/GPU/disk/results/log 상태 출력 | 평가/학습은 실행하지 않음 |
| `scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py` | `results.csv`, checkpoint, 결과 디렉터리, log tail 요약 | CPU-only parser, 모델 로드/평가 없음 |

## 파이프라인 단계

기존 실행/재개 스크립트 기준 순서:

1. **Stage1 train**
   - run: `walksafe_tactile3_yolo26s_img960_musgd_e200_20260521`
   - `imgsz=960`, `epochs=200`, `batch=8`, `optimizer=MuSGD`
   - output: `runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/`
2. **Stage1 test val**
   - model: Stage1 `weights/best.pt`
   - split: `test`, `imgsz=960`
   - output: `runs/validation/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521_test/`
3. **Stage2 1280 finetune**
   - run: `walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521`
   - source model: Stage1 `weights/best.pt`
   - `imgsz=1280`, `epochs=80`, `batch=4`, `mosaic=0.0`, `close_mosaic=0`
   - output: `runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521/`
4. **Stage2 test val**
   - model: Stage2 `weights/best.pt`
   - split: `test`, `imgsz=1280`
   - output: `runs/validation/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521_test/`

## 1. 진행 상태 확인

현재 학습 중에는 아래 상태 확인만 수행한다. 별도의 `yolo detect val`, `predict`, `train` 명령은 실행하지 않는다.

```bash
cd /home/ddobagi/Code/hanium-dreamup
bash scripts/check_walksafe_tactile3_training_status_20260522.sh
```

수동 확인이 필요하면 CPU/파일 조회 명령만 사용한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup
pgrep -af 'resume_walksafe_tactile3_yolo26s_20260522|run_walksafe_tactile3_yolo26s_20260521|\.venv/bin/yolo detect (train|val)' || true

df -h /home/ddobagi/Code/hanium-dreamup
ls -lh runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/ 2>/dev/null || true
ls -lh runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521/weights/ 2>/dev/null || true

tail -n 80 logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log 2>/dev/null || \
  tail -n 80 logs/walksafe_tactile3_yolo26s_pipeline_20260521.log 2>/dev/null || true
```

완료 판정은 최소한 아래를 확인한다.

- Stage1/Stage2 학습 프로세스가 남아 있지 않다.
- 로그에 `TACTILE3 YOLO26S PIPELINE DONE` 또는 `TACTILE3 YOLO26S RESUME PIPELINE DONE`이 있다.
- Stage1/Stage2 `weights/best.pt`, `weights/last.pt`가 존재한다.
- Stage1/Stage2 test output 디렉터리가 비어 있지 않다.

## 2. 완료 후 요약 리포트 생성

요약 스크립트는 CPU-only parser다. `results.csv`와 파일 메타데이터/log tail만 읽고 YOLO 평가나 추론을 실행하지 않는다.

### Stage1 요약

```bash
cd /home/ddobagi/Code/hanium-dreamup
mkdir -p reports/runs/evaluations/walksafe_tactile3_yolo26s_20260522
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py \
  --results-csv runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv \
  --include-log-tail 20 \
  --out-json reports/runs/evaluations/walksafe_tactile3_yolo26s_20260522/stage1_summary.json \
  --out-md reports/runs/evaluations/walksafe_tactile3_yolo26s_20260522/stage1_summary.md
```

### Stage2 요약

```bash
cd /home/ddobagi/Code/hanium-dreamup
mkdir -p reports/runs/evaluations/walksafe_tactile3_yolo26s_20260522
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py \
  --results-csv runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521/results.csv \
  --include-log-tail 20 \
  --out-json reports/runs/evaluations/walksafe_tactile3_yolo26s_20260522/stage2_summary.json \
  --out-md reports/runs/evaluations/walksafe_tactile3_yolo26s_20260522/stage2_summary.md
```

helper를 사용할 경우:

```bash
cd /home/ddobagi/Code/hanium-dreamup
bash scripts/post_yolo26s_training_report_20260522.sh
```

## 3. `best.pt` / `last.pt` 선택 기준

| 상황 | 선택 | 이유 |
| --- | --- | --- |
| 학습 정상 완료 후 평가/보고/후보 배포 | `weights/best.pt` | 학습 중 validation 기준으로 선택된 checkpoint이며 `last.pt`보다 일반화 후보로 적합 |
| 학습 중단 후 재개 | `weights/last.pt` | optimizer/scheduler 등 재개 상태 기준 |
| `best.pt` 누락/손상 | `last.pt` 사용 여부 별도 판단 | 누락 원인 확인 전 최종 후보로 단정하지 않음 |
| Stage2가 Stage1보다 test/val 지표가 명확히 나쁨 | Stage1 `best.pt` 유지 가능 | 1280 fine-tune이 항상 개선된다고 가정하지 않음 |

원칙:

- 최종 후보는 먼저 Stage2 `best.pt`를 검토하되, Stage1 대비 성능/안정성이 악화되면 Stage1 `best.pt`도 후보로 유지한다.
- `last.pt`는 기본적으로 재개용이다. 최종 후보로 쓰려면 `best.pt` 대비 선택 이유를 리포트에 명시한다.
- 최종 test 결과를 여러 번 반복해 checkpoint를 고르는 방식은 금지한다.

## 4. Stage1 vs Stage2 비교 절차

1. Stage1/Stage2 summary Markdown을 생성한다.
2. 아래 항목을 같은 표에 옮긴다.
   - completed epochs
   - best `metrics/mAP50-95(B)` epoch/value
   - best `metrics/mAP50(B)` epoch/value
   - last epoch precision/recall/mAP
   - `val/box_loss`, `val/cls_loss`, `val/dfl_loss` 추세
   - test output 디렉터리 존재 여부
3. Stage1 test와 Stage2 test 결과 파일을 1회 비교한다.
   - 비교 대상: `results.csv`, `confusion_matrix*`, `PR_curve*`, `F1_curve*`, 예측 샘플 이미지가 있으면 오류 유형
   - Stage2가 mAP만 소폭 개선되고 recall/오류 유형이 악화되면 바로 배포 후보로 단정하지 않는다.
4. 결론은 아래 중 하나로 기록한다.
   - Stage2 `best.pt` 채택
   - Stage1 `best.pt` 유지
   - 추가 validation/calibration 필요

CPU-only 파일 목록 확인 예시:

```bash
cd /home/ddobagi/Code/hanium-dreamup
find runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521 \
  runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521 \
  runs/validation/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521_test \
  runs/validation/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521_test \
  -maxdepth 2 -type f \
  \( -name 'results.csv' -o -name '*curve*' -o -name 'confusion_matrix*' \) \
  -print 2>/dev/null | sort
```

## 5. Threshold 후보 정리 절차

현재 단계에서는 새 평가를 실행하지 않고 후보만 정리한다.

1. 기준 후보를 미리 고정한다.
   - `conf=0.25`: YOLO 기본에 가까운 baseline 후보
   - validation `F1_curve` 피크 근처: 균형 후보
   - recall 우선 후보: 사용자 안전상 누락을 줄이는 후보
   - precision 우선 후보: 오탐 알림 비용을 줄이는 후보
2. 후보 근거는 train/validation 과정에서 이미 생성된 artifact에서만 확인한다.
   - Stage1 train dir의 `F1_curve*`, `P_curve*`, `R_curve*`, `PR_curve*`
   - Stage2 train dir의 동일 파일
3. 최종 test output에서 threshold를 반복 조정하지 않는다.
4. threshold 확정이 필요하면 별도 calibration/validation split 기준으로 1회 계획을 세운 뒤 실행한다. 최종 test는 확정된 설정을 마지막에 한 번 확인하는 용도다.

후보 기록 템플릿:

| 후보 | 근거 artifact | 기대 효과 | 위험 |
| --- | --- | --- | --- |
| 0.25 | baseline | 기본 비교점 | 오탐 가능 |
| F1 peak 근처 | validation `F1_curve` | precision/recall 균형 | 운영 목적과 다를 수 있음 |
| recall 우선 | validation `R_curve` | 위험 객체 누락 감소 | 오탐 증가 |
| precision 우선 | validation `P_curve` | 오탐 감소 | 미탐 증가 |

## 6. 실패/중단 시 재개 기준

재개 명령을 바로 실행하지 말고, 먼저 중단 위치와 원인을 파일/로그로 확인한다.

### 6.1 로그에서 중단 위치 확인

```bash
cd /home/ddobagi/Code/hanium-dreamup
LOG=logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log
[[ -f "$LOG" ]] || LOG=logs/walksafe_tactile3_yolo26s_pipeline_20260521.log

grep -E 'PIPELINE START|RESUME PIPELINE START|BASE TEST VAL START|HIGH-RES FINETUNE START|HIGH-RES TEST VAL START|PIPELINE DONE|RESUME PIPELINE DONE|FAILED' "$LOG" || true
tail -n 120 "$LOG" || true
```

### 6.2 checkpoint 확인

```bash
cd /home/ddobagi/Code/hanium-dreamup
ls -lh runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/weights/{last.pt,best.pt} 2>/dev/null || true
ls -lh runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521/weights/{last.pt,best.pt} 2>/dev/null || true
```

판정 기준:

- Stage1 train 중단: Stage1 `weights/last.pt`가 있으면 재개 후보.
- Stage1 완료 후 Stage1 test val에서 중단: Stage1 `best.pt`와 로그를 확인한 뒤 test val만 재실행할지, 전체 resume script를 쓸지 별도 결정한다.
- Stage2 train 중단: Stage2 `weights/last.pt`가 있으면 Stage2 재개 후보. 현재 제공된 resume script는 Stage1 resume 중심이므로 중복 실행 위험을 확인한다.
- Stage2 test val 중단: Stage2 `best.pt`가 있으면 test val만 재실행 후보. 단, 현재 진행 중인 학습/평가가 없는지 먼저 확인한다.

### 6.3 디스크/OOM/런타임 오류 확인

```bash
cd /home/ddobagi/Code/hanium-dreamup
LOG=logs/walksafe_tactile3_yolo26s_resume_pipeline_20260522.log
[[ -f "$LOG" ]] || LOG=logs/walksafe_tactile3_yolo26s_pipeline_20260521.log

df -h /home/ddobagi/Code/hanium-dreamup
du -sh runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521 \
  runs/detect/walksafe_tactile3_yolo26s_img1280_ft80_nomosaic_20260521 \
  runs/validation 2>/dev/null || true

grep -Ei 'CUDA out of memory|out of memory|OOM|Killed|No space left|Traceback|RuntimeError' "$LOG" || true
```

확인 기준:

- `No space left`/디스크 95% 이상: 정리 계획 없이 재개하지 않는다.
- `CUDA out of memory`: batch/workers/imgsz 조정 필요 여부를 별도 판단한다. 임의로 hyperparameter를 바꾸지 않는다.
- `Killed`/Traceback: 마지막 stack trace와 checkpoint mtime을 함께 기록한다.
- 원인 불명확: 재개 전 담당자에게 중단 위치, 로그 tail, checkpoint 상태를 공유한다.

## 7. 최종 test 반복 tuning 금지

- Stage1/Stage2 test val은 pipeline의 비교/판정용 결과다.
- test 결과를 보고 threshold, checkpoint, augmentation, image size를 여러 번 바꿔 다시 test하는 방식은 금지한다.
- 추가 조정이 필요하면 validation/calibration 기준으로 먼저 고정하고, 최종 test는 고정된 설정의 마지막 확인으로만 사용한다.
