#!/usr/bin/env python3
"""CPU-only post-training summary for the reviewed WalkSafe tactile3 YOLO26s run.

This script only reads results.csv, log text, and filesystem metadata. It does not
import torch/ultralytics, load checkpoints, or run train/val/predict.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import sys
from collections import deque
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

STAGE1_RUN_DIR = Path("runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522")
STAGE2_RUN_DIR = Path("runs/detect/walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522")
STAGE1_VAL_DIR = Path("runs/validation/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522_test")
STAGE2_VAL_DIR = Path("runs/validation/walksafe_tactile3_reviewed_yolo26s_img1280_ft80_nomosaic_20260522_test")
DEFAULT_LOG = Path("logs/walksafe_tactile3_reviewed_yolo26s_pipeline_20260522.log")

MAP50_95_COL = "metrics/mAP50-95(B)"
MAP50_COL = "metrics/mAP50(B)"
KEY_METRIC_COLUMNS = [
    "epoch",
    "time",
    "metrics/precision(B)",
    "metrics/recall(B)",
    MAP50_COL,
    MAP50_95_COL,
    "train/box_loss",
    "train/cls_loss",
    "train/dfl_loss",
    "val/box_loss",
    "val/cls_loss",
    "val/dfl_loss",
]
ANSI_ESCAPE_RE = re.compile(
    r"""
    \x1B
    (?:
        \[[0-?]*[ -/]*[@-~]
        | \][^\x07]*(?:\x07|\x1B\\)
        | [@-Z\\-_]
    )
    """,
    re.VERBOSE,
)

Row = dict[str, Any]
ParsedRow = dict[str, Any]


def nonnegative_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a non-negative integer") from exc
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be a non-negative integer")
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "CPU-only JSON/Markdown report for the reviewed YOLO26s Stage1/Stage2 run. "
            "Reads existing files only; no GPU training/validation/inference is executed."
        )
    )
    parser.add_argument("--stage1-run-dir", type=Path, default=STAGE1_RUN_DIR)
    parser.add_argument("--stage2-run-dir", type=Path, default=STAGE2_RUN_DIR)
    parser.add_argument("--stage1-val-dir", type=Path, default=STAGE1_VAL_DIR)
    parser.add_argument("--stage2-val-dir", type=Path, default=STAGE2_VAL_DIR)
    parser.add_argument("--log", type=Path, default=DEFAULT_LOG)
    parser.add_argument("--out-json", type=Path, help="Optional JSON report path")
    parser.add_argument("--out-md", type=Path, help="Optional Markdown report path")
    parser.add_argument(
        "--include-log-tail",
        type=nonnegative_int,
        metavar="N",
        help="Include the last meaningful N lines from --log, with ANSI escapes stripped.",
    )
    return parser.parse_args()


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def file_time_iso(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).astimezone().isoformat(timespec="seconds")


def parse_cell(value: str | None) -> Any:
    if value is None:
        return None
    text = value.strip()
    if not text:
        return None
    try:
        number = float(text)
    except ValueError:
        return text
    if not math.isfinite(number):
        return None
    if number.is_integer() and re.fullmatch(r"[+-]?\d+", text):
        return int(number)
    return number


def numeric_value(row: Row | None, column: str) -> float | None:
    if not row:
        return None
    value = row.get(column)
    if isinstance(value, bool) or value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def normalize_columns(raw_columns: list[str], warnings: list[str]) -> list[str]:
    columns: list[str] = []
    seen: dict[str, int] = {}
    for index, raw_name in enumerate(raw_columns, start=1):
        name = raw_name.strip() or f"unnamed_{index}"
        if name in seen:
            seen[name] += 1
            deduped = f"{name}#{seen[name]}"
            warnings.append(f"Duplicate column '{name}' was renamed to '{deduped}'.")
            name = deduped
        else:
            seen[name] = 1
        columns.append(name)
    return columns


def read_results_csv(path: Path) -> tuple[list[str], list[ParsedRow], list[str], list[str]]:
    warnings: list[str] = []
    errors: list[str] = []
    rows: list[ParsedRow] = []

    if not path.exists():
        return [], [], warnings, [f"results.csv not found: {path}"]
    if not path.is_file():
        return [], [], warnings, [f"results.csv path is not a file: {path}"]

    try:
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.reader(handle)
            try:
                raw_columns = next(reader)
            except StopIteration:
                return [], [], warnings, [f"results.csv is empty: {path}"]
            columns = normalize_columns(raw_columns, warnings)
            for line_number, raw_values in enumerate(reader, start=2):
                if not any((value or "").strip() for value in raw_values):
                    continue
                if len(raw_values) != len(columns):
                    warnings.append(
                        f"Line {line_number}: expected {len(columns)} columns, found {len(raw_values)}; "
                        "missing values were filled as null."
                    )
                padded = raw_values[: len(columns)] + [""] * max(0, len(columns) - len(raw_values))
                rows.append(
                    {
                        "source_line": line_number,
                        "metrics": {column: parse_cell(padded[index]) for index, column in enumerate(columns)},
                    }
                )
    except OSError as exc:
        errors.append(f"Failed to read results.csv: {exc}")
    except csv.Error as exc:
        errors.append(f"Failed to parse results.csv: {exc}")

    if not rows and not errors:
        errors.append(f"No epoch rows found in results.csv: {path}")
    return columns if 'columns' in locals() else [], rows, warnings, errors


def select_best(rows: list[ParsedRow], column: str) -> ParsedRow | None:
    best_row: ParsedRow | None = None
    best_value: float | None = None
    for row in rows:
        value = numeric_value(row["metrics"], column)
        if value is None:
            continue
        if best_value is None or value > best_value:
            best_value = value
            best_row = row
    return best_row


def row_summary(row: ParsedRow | None, selected_by: str | None = None) -> dict[str, Any] | None:
    if row is None:
        return None
    return {
        "source_line": row["source_line"],
        "selected_by": selected_by,
        "epoch": row["metrics"].get("epoch"),
        "metrics": row["metrics"],
    }


def human_size(size_bytes: int | None) -> str | None:
    if size_bytes is None:
        return None
    size = float(size_bytes)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if size < 1024.0 or unit == "GiB":
            return f"{int(size)} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size_bytes} B"


def path_stat(path: Path, *, expect_dir: bool = False) -> dict[str, Any]:
    exists = path.is_dir() if expect_dir else path.exists()
    info: dict[str, Any] = {"path": str(path), "exists": exists}
    if not exists:
        return info
    try:
        stat = path.stat()
    except OSError as exc:
        info["stat_error"] = str(exc)
        return info
    info["mtime"] = file_time_iso(stat.st_mtime)
    info["mtime_unix"] = stat.st_mtime
    if expect_dir:
        try:
            info["entry_count"] = sum(1 for _ in path.iterdir())
        except OSError as exc:
            info["entry_count_error"] = str(exc)
    else:
        info["is_file"] = path.is_file()
        info["size_bytes"] = stat.st_size
        info["size_human"] = human_size(stat.st_size)
    return info


def checkpoint_summary(run_dir: Path) -> dict[str, dict[str, Any]]:
    return {
        "best.pt": path_stat(run_dir / "weights" / "best.pt"),
        "last.pt": path_stat(run_dir / "weights" / "last.pt"),
    }


def stage_summary(label: str, run_dir: Path, val_dir: Path, expected_epochs: int) -> dict[str, Any]:
    results_csv = run_dir / "results.csv"
    columns, rows, warnings, errors = read_results_csv(results_csv)
    if columns and "epoch" not in columns:
        warnings.append("Column 'epoch' is missing; completed_epochs uses parsed row count.")
    for column in (MAP50_95_COL, MAP50_COL):
        if columns and column not in columns:
            warnings.append(f"Column '{column}' is missing; best epoch for this metric is unavailable.")

    last_row = rows[-1] if rows else None
    best_map50_95 = select_best(rows, MAP50_95_COL) if MAP50_95_COL in columns else None
    best_map50 = select_best(rows, MAP50_COL) if MAP50_COL in columns else None
    completed_epochs = len(rows)
    checkpoints = checkpoint_summary(run_dir)
    run_info = path_stat(run_dir, expect_dir=True)
    val_info = path_stat(val_dir, expect_dir=True)

    val_exists = bool(val_info.get("exists"))
    if errors and not rows:
        status = "missing_or_unreadable"
        status_reason = "results.csv is missing or unreadable."
    elif completed_epochs < expected_epochs and val_exists:
        status = "finished_or_early_stopped"
        status_reason = (
            f"results.csv has {completed_epochs}/{expected_epochs} rows, but the validation "
            "directory exists; training likely advanced past this stage or early-stopped."
        )
    elif completed_epochs < expected_epochs:
        status = "in_progress_or_incomplete"
        status_reason = f"results.csv has {completed_epochs}/{expected_epochs} rows."
    elif not checkpoints["best.pt"].get("exists") or not checkpoints["last.pt"].get("exists"):
        status = "incomplete_checkpoints"
        status_reason = "expected checkpoint file is missing."
    else:
        status = "complete_by_results_csv"
        status_reason = "expected epoch count and checkpoints are present."

    return {
        "label": label,
        "status": status,
        "status_reason": status_reason,
        "expected_epochs": expected_epochs,
        "completed_epochs": completed_epochs,
        "results_csv": str(results_csv),
        "run_dir": run_info,
        "validation_dir": val_info,
        "last_epoch": row_summary(last_row, selected_by="last_row"),
        "best": {
            "map50_95": row_summary(best_map50_95, selected_by=MAP50_95_COL),
            "map50": row_summary(best_map50, selected_by=MAP50_COL),
        },
        "checkpoints": checkpoints,
        "warnings": warnings,
        "errors": errors,
    }


def best_map(stage: dict[str, Any]) -> float | None:
    row = stage.get("best", {}).get("map50_95")
    return numeric_value(row.get("metrics") if row else None, MAP50_95_COL)


def choose_winner(stage1: dict[str, Any], stage2: dict[str, Any]) -> dict[str, Any]:
    s1 = best_map(stage1)
    s2 = best_map(stage2)
    stage2_test_present = bool(stage2.get("validation_dir", {}).get("exists"))
    provisional = not stage2_test_present
    reason_parts = []

    if s1 is None and s2 is None:
        winner = None
        reason_parts.append("No usable Stage1/Stage2 mAP50-95 metric is available yet.")
    elif s2 is None:
        winner = "stage1"
        reason_parts.append("Stage2 mAP50-95 is unavailable, so Stage1 is the current fallback.")
    elif s1 is None:
        winner = "stage2"
        reason_parts.append("Stage1 mAP50-95 is unavailable and Stage2 has a usable metric.")
    elif s2 >= s1:
        winner = "stage2"
        reason_parts.append(f"Stage2 best mAP50-95 ({s2:.6g}) >= Stage1 ({s1:.6g}).")
    else:
        winner = "stage1"
        reason_parts.append(f"Stage1 best mAP50-95 ({s1:.6g}) > Stage2 ({s2:.6g}).")

    if provisional:
        reason_parts.append("Stage2 test validation directory is missing, so this model choice is provisional.")
    else:
        reason_parts.append("Stage2 test validation directory exists; still confirm its artifacts before final adoption.")

    return {
        "winner": winner,
        "metric": MAP50_95_COL,
        "stage1_value": s1,
        "stage2_value": s2,
        "provisional": provisional,
        "reason": " ".join(reason_parts),
    }


def strip_ansi(text: str) -> str:
    return ANSI_ESCAPE_RE.sub("", text)


def read_log_tail(path: Path, limit: int) -> dict[str, Any]:
    info: dict[str, Any] = {"path": str(path), "requested_lines": limit, "exists": path.is_file(), "lines": []}
    if limit == 0 or not path.is_file():
        return info
    tail: deque[str] = deque(maxlen=limit)
    try:
        with path.open("r", encoding="utf-8", errors="replace") as handle:
            for raw_line in handle:
                for part in raw_line.splitlines():
                    line = strip_ansi(part).strip()
                    if line:
                        tail.append(line)
        stat = path.stat()
    except OSError as exc:
        info["error"] = str(exc)
        return info
    info.update({"lines": list(tail), "mtime": file_time_iso(stat.st_mtime), "size_human": human_size(stat.st_size)})
    return info


def build_report(args: argparse.Namespace) -> dict[str, Any]:
    stage1 = stage_summary("stage1", args.stage1_run_dir, args.stage1_val_dir, 200)
    stage2 = stage_summary("stage2", args.stage2_run_dir, args.stage2_val_dir, 80)
    report: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": now_iso(),
        "parser": "scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py",
        "cpu_only": True,
        "notes": [
            "Reads existing results.csv/log/filesystem metadata only.",
            "Does not import torch/ultralytics or run train/val/predict.",
        ],
        "stages": {"stage1": stage1, "stage2": stage2},
        "provisional_winner": choose_winner(stage1, stage2),
    }
    if args.include_log_tail is not None:
        report["log_tail"] = read_log_tail(args.log, args.include_log_tail)
    return report


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return f"{value:.6g}" if math.isfinite(value) else "-"
    return str(value).replace("|", "\\|")


def metric_table_row(label: str, row: dict[str, Any] | None) -> list[str]:
    metrics = row.get("metrics", {}) if row else {}
    return [
        label,
        fmt(metrics.get("epoch")),
        fmt(metrics.get(MAP50_95_COL)),
        fmt(metrics.get(MAP50_COL)),
        fmt(metrics.get("metrics/precision(B)")),
        fmt(metrics.get("metrics/recall(B)")),
        fmt(metrics.get("val/box_loss")),
        fmt(metrics.get("val/cls_loss")),
        fmt(metrics.get("val/dfl_loss")),
    ]


def render_markdown(report: dict[str, Any]) -> str:
    winner = report["provisional_winner"]
    lines = [
        "# Reviewed WalkSafe tactile3 YOLO26s Post-training Summary",
        "",
        "> CPU-only parser: existing `results.csv`, log text, and filesystem metadata only. No GPU train/val/inference.",
        "",
        "## Model selection snapshot",
        "",
        "| Item | Value |",
        "| --- | --- |",
        f"| Generated at | {fmt(report['generated_at'])} |",
        f"| Provisional winner | {fmt(winner.get('winner'))} |",
        f"| Provisional? | {fmt(winner.get('provisional'))} |",
        f"| Criterion | `{fmt(winner.get('metric'))}` |",
        f"| Stage1 value | {fmt(winner.get('stage1_value'))} |",
        f"| Stage2 value | {fmt(winner.get('stage2_value'))} |",
        f"| Reason | {fmt(winner.get('reason'))} |",
        "",
        "## Stage summaries",
        "",
        "| Stage | Status | Reason | Completed epochs | Run dir exists | Validation dir exists | best.pt | last.pt |",
        "| --- | --- | --- | ---: | --- | --- | --- | --- |",
    ]
    for name, stage in report["stages"].items():
        checkpoints = stage.get("checkpoints", {})
        lines.append(
            "| "
            + " | ".join(
                [
                    fmt(name),
                    fmt(stage.get("status")),
                    fmt(stage.get("status_reason")),
                    f"{fmt(stage.get('completed_epochs'))}/{fmt(stage.get('expected_epochs'))}",
                    fmt(stage.get("run_dir", {}).get("exists")),
                    fmt(stage.get("validation_dir", {}).get("exists")),
                    fmt(checkpoints.get("best.pt", {}).get("exists")),
                    fmt(checkpoints.get("last.pt", {}).get("exists")),
                ]
            )
            + " |"
        )

    for name, stage in report["stages"].items():
        lines.extend(
            [
                "",
                f"## {name} metrics",
                "",
                f"- results.csv: `{fmt(stage.get('results_csv'))}`",
                f"- run dir: `{fmt(stage.get('run_dir', {}).get('path'))}`",
                f"- validation dir: `{fmt(stage.get('validation_dir', {}).get('path'))}`",
                "",
                "| Selection | epoch | mAP50-95(B) | mAP50(B) | precision(B) | recall(B) | val/box_loss | val/cls_loss | val/dfl_loss |",
                "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
            ]
        )
        for row in (
            metric_table_row("last", stage.get("last_epoch")),
            metric_table_row("best mAP50-95", stage.get("best", {}).get("map50_95")),
            metric_table_row("best mAP50", stage.get("best", {}).get("map50")),
        ):
            lines.append("| " + " | ".join(row) + " |")

        lines.extend(["", "### Checkpoints", "", "| File | Exists | mtime | Size | Path |", "| --- | --- | --- | ---: | --- |"])
        for ckpt_name, info in stage.get("checkpoints", {}).items():
            lines.append(
                "| "
                + " | ".join(
                    [
                        fmt(ckpt_name),
                        fmt(info.get("exists")),
                        fmt(info.get("mtime")),
                        fmt(info.get("size_human") or info.get("size_bytes")),
                        f"`{fmt(info.get('path'))}`",
                    ]
                )
                + " |"
            )
        if stage.get("warnings"):
            lines.extend(["", "### Warnings", ""])
            lines.extend(f"- {fmt(item)}" for item in stage["warnings"])
        if stage.get("errors"):
            lines.extend(["", "### Errors", ""])
            lines.extend(f"- {fmt(item)}" for item in stage["errors"])

    log_tail = report.get("log_tail")
    if log_tail:
        lines.extend(
            [
                "",
                "## Log tail",
                "",
                f"- source: `{fmt(log_tail.get('path'))}`",
                f"- exists: {fmt(log_tail.get('exists'))}",
                f"- requested lines: {fmt(log_tail.get('requested_lines'))}",
                "",
            ]
        )
        if log_tail.get("lines"):
            for line in log_tail["lines"]:
                lines.append(f"    {line}")
        elif log_tail.get("error"):
            lines.append(f"- {fmt(log_tail['error'])}")
        else:
            lines.append("- No log lines found.")

    return "\n".join(lines) + "\n"


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    args = parse_args()
    report = build_report(args)
    wrote_output = False
    if args.out_json:
        write_text(args.out_json, json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        wrote_output = True
    if args.out_md:
        write_text(args.out_md, render_markdown(report))
        wrote_output = True
    if not wrote_output:
        sys.stdout.write(render_markdown(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
