# Model report automation notes (2026-05-22)

## Scope

- Script: `scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py`
- CPU-only parser: reads `results.csv`, checkpoint/result directory metadata, and optional log tail only.
- It must not run YOLO inference, validation, evaluation, training, or GPU work.
- No long-running watcher option is provided.

## Usage examples

Print the current Markdown summary:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py
```

Include the last 20 meaningful lines from the resume log when available, otherwise the original pipeline log. ANSI escape sequences are stripped before output:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py --include-log-tail 20
```

Write both machine-readable JSON and Markdown reports:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py \
  --include-log-tail 20 \
  --out-json /tmp/walksafe_yolo26s_summary.json \
  --out-md /tmp/walksafe_yolo26s_summary.md
```

Use a non-default `results.csv`:

```bash
python3 scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py \
  --results-csv runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv
```

## Result directory status meaning

- `present`: output directory exists and basic expected files/entries are visible.
- `missing_ok`: output is missing, but that is expected for the current pipeline point.
- `incomplete_possible`: output exists but looks partial, or a downstream output is missing after prerequisites appear available.

For training directories, the script checks `results.csv` epoch count and `weights/best.pt` / `weights/last.pt` presence. An `incomplete_possible` status is intentionally conservative: early stopping or an in-progress run can both produce fewer rows than the configured epoch count.
