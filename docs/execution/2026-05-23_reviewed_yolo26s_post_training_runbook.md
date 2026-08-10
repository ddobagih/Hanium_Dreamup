# Reviewed YOLO26s 학습 완료 직후 리포트 Runbook (2026-05-23)

## 범위

- 작업 위치: `/home/ddobagi/Code/hanium-dreamup`
- 대상 run:
  - Stage1: `runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522`
  - Stage2: `runs/detect/walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522`
- 이 runbook은 **CPU-only 파일 요약**만 다룬다.
- 금지: `yolo detect train`, `yolo detect val`, `predict`, checkpoint 로드, GPU inference/validation 재실행.

## 완료 직후 실행

```bash
cd /home/ddobagi/Code/hanium-dreamup
bash scripts/post_reviewed_yolo26s_training_report_20260523.sh
```

출력 기본 위치:

```text
reports/runs/evaluations/walksafe_tactile3_reviewed_yolo26s_20260523/
  reviewed_yolo26s_stage1_stage2_summary.json
  reviewed_yolo26s_stage1_stage2_summary.md
```

로그 tail 줄 수를 바꾸려면:

```bash
INCLUDE_LOG_TAIL=80 bash scripts/post_reviewed_yolo26s_training_report_20260523.sh
```

## 직접 실행

```bash
cd /home/ddobagi/Code/hanium-dreamup
CUDA_VISIBLE_DEVICES="" python3 scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py \
  --include-log-tail 40 \
  --out-json reports/runs/evaluations/walksafe_tactile3_reviewed_yolo26s_20260523/reviewed_yolo26s_stage1_stage2_summary.json \
  --out-md reports/runs/evaluations/walksafe_tactile3_reviewed_yolo26s_20260523/reviewed_yolo26s_stage1_stage2_summary.md
```

## 리포트에서 확인할 항목

1. Stage1/Stage2 run dir 존재 여부
2. Stage1/Stage2 test validation dir 존재 여부
3. 각 stage의 `completed_epochs`
4. last row metrics
5. best `metrics/mAP50-95(B)` row
6. `weights/best.pt`, `weights/last.pt` 존재 여부
7. `provisional_winner`

주의:

- Stage2 test validation dir가 없으면 winner는 반드시 provisional로 본다.
- 이 도구의 winner 판단은 기존 `results.csv`의 best mAP50-95 기준 1차 판단이다.
- 최종 채택 전에는 이미 생성된 test validation artifact의 존재와 내용을 별도 확인한다. 새 GPU 평가를 추가 실행하지 않는다.

## 빠른 상태 확인(읽기 전용)

```bash
cd /home/ddobagi/Code/hanium-dreamup
python3 scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py --include-log-tail 20 | sed -n '1,120p'
```

## 검증 명령

```bash
cd /home/ddobagi/Code/hanium-dreamup
python3 -m py_compile scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py
python3 scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py --help
bash -n scripts/post_reviewed_yolo26s_training_report_20260523.sh
```
