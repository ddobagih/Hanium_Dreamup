#!/usr/bin/env python3
"""Summarize the 2026-05-21 WalkSafe tactile3 YOLO26s run.

CPU-only parser: reads Ultralytics results.csv and filesystem metadata only.
It does not import torch/ultralytics and does not run inference or evaluation.
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

DEFAULT_RESULTS_CSV = Path(
    "runs/detect/walksafe_tactile3_yolo26s_img960_musgd_e200_20260521/results.csv"
)

MAP50_95_COL = "metrics/mAP50-95(B)"
MAP50_COL = "metrics/mAP50(B)"
EXPECTED_STAGE1_EPOCHS = 200
EXPECTED_STAGE2_EPOCHS = 80
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
            "CPU-only summary for WalkSafe tactile3 YOLO26s results.csv. "
            "No GPU inference/evaluation is executed."
        )
    )
    parser.add_argument(
        "--results-csv",
        type=Path,
        default=DEFAULT_RESULTS_CSV,
        help=f"Ultralytics results.csv path (default: {DEFAULT_RESULTS_CSV})",
    )
    parser.add_argument("--out-json", type=Path, help="Optional JSON summary output path")
    parser.add_argument("--out-md", type=Path, help="Optional Markdown summary output path")
    parser.add_argument(
        "--include-log-tail",
        type=nonnegative_int,
        metavar="N",
        help=(
            "Include the last meaningful N lines from the resume log when present, "
            "otherwise the original pipeline log. ANSI escapes are stripped."
        ),
    )
    return parser.parse_args()


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def file_time_iso(timestamp: float) -> str:
    return datetime.fromtimestamp(timestamp, timezone.utc).astimezone().isoformat(
        timespec="seconds"
    )


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
    if number.is_integer() and text.isdigit():
        return int(number)
    return number


def numeric_value(row: Row, column: str) -> float | None:
    value = row.get(column)
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int | float):
        number = float(value)
    else:
        try:
            number = float(str(value).strip())
        except ValueError:
            return None
    if not math.isfinite(number):
        return None
    return number


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
                        f"Line {line_number}: expected {len(columns)} columns, "
                        f"found {len(raw_values)}; missing values were filled as null."
                    )

                padded_values = raw_values[: len(columns)] + [""] * max(
                    0, len(columns) - len(raw_values)
                )
                metrics = {
                    column: parse_cell(padded_values[index])
                    for index, column in enumerate(columns)
                }
                rows.append({"source_line": line_number, "metrics": metrics})
    except OSError as exc:
        errors.append(f"Failed to read results.csv: {exc}")
        return [], [], warnings, errors
    except csv.Error as exc:
        errors.append(f"Failed to parse results.csv: {exc}")
        return [], [], warnings, errors

    if not rows:
        errors.append(f"No epoch rows found in results.csv: {path}")
    return columns, rows, warnings, errors


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
    metrics = row["metrics"]
    return {
        "source_line": row["source_line"],
        "selected_by": selected_by,
        "epoch": metrics.get("epoch"),
        "metrics": metrics,
    }


def select_top(rows: list[ParsedRow], column: str, limit: int = 5) -> list[dict[str, Any]]:
    scored_rows: list[tuple[float, ParsedRow]] = []
    for row in rows:
        value = numeric_value(row["metrics"], column)
        if value is not None:
            scored_rows.append((value, row))

    scored_rows.sort(key=lambda item: (-item[0], item[1]["source_line"]))

    top_rows: list[dict[str, Any]] = []
    for rank, (value, row) in enumerate(scored_rows[:limit], start=1):
        summary = row_summary(row, selected_by=column)
        if summary is None:
            continue
        summary["rank"] = rank
        summary["value"] = value
        top_rows.append(summary)
    return top_rows


def human_size(size_bytes: int | None) -> str | None:
    if size_bytes is None:
        return None
    size = float(size_bytes)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if size < 1024.0 or unit == "GiB":
            if unit == "B":
                return f"{int(size)} {unit}"
            return f"{size:.1f} {unit}"
        size /= 1024.0
    return f"{size_bytes} B"


def path_stat(path: Path, *, expect_dir: bool = False) -> dict[str, Any]:
    exists = path.is_dir() if expect_dir else path.exists()
    info: dict[str, Any] = {
        "path": str(path),
        "exists": exists,
        "status": "present" if exists else "missing_ok",
    }
    if exists:
        try:
            stat = path.stat()
        except OSError as exc:
            info["status"] = "stat_error"
            info["error"] = str(exc)
            return info
        info["mtime"] = file_time_iso(stat.st_mtime)
        info["mtime_unix"] = stat.st_mtime
        if not expect_dir:
            info["is_file"] = path.is_file()
            info["size_bytes"] = stat.st_size
            info["size_human"] = human_size(stat.st_size)
    return info


def count_dir_entries(path: Path) -> int | None:
    try:
        return sum(1 for _ in path.iterdir())
    except OSError:
        return None


def assess_train_result_dir(
    path: Path,
    *,
    expected_epochs: int,
    known_completed_epochs: int | None = None,
    missing_reason: str,
) -> dict[str, Any]:
    info = path_stat(path, expect_dir=True)
    info["kind"] = "train"
    info["expected_epochs"] = expected_epochs
    info["results_csv"] = str(path / "results.csv")

    if not info["exists"]:
        info["status"] = "missing_ok"
        info["reason"] = missing_reason
        return info

    if known_completed_epochs is None:
        _, rows, _, errors = read_results_csv(path / "results.csv")
        completed_epochs = len(rows)
        if errors:
            info["results_csv_errors"] = errors
    else:
        completed_epochs = known_completed_epochs

    info["completed_epochs"] = completed_epochs
    best_pt = path / "weights" / "best.pt"
    last_pt = path / "weights" / "last.pt"
    info["checkpoint_status"] = {
        "best.pt": "present" if best_pt.is_file() else "missing",
        "last.pt": "present" if last_pt.is_file() else "missing",
    }

    missing_checkpoints = [
        name for name, status in info["checkpoint_status"].items() if status != "present"
    ]
    if completed_epochs < expected_epochs:
        info["status"] = "incomplete_possible"
        info["reason"] = (
            f"results.csv has {completed_epochs}/{expected_epochs} expected epochs; "
            "training may still be running, interrupted, or early-stopped."
        )
    elif missing_checkpoints:
        info["status"] = "incomplete_possible"
        info["reason"] = f"missing checkpoint(s): {', '.join(missing_checkpoints)}"
    else:
        info["status"] = "present"
        info["reason"] = "expected results.csv epoch count and checkpoints are present."
    return info


def assess_followup_result_dir(
    path: Path,
    *,
    kind: str,
    missing_ok: bool,
    missing_reason: str,
) -> dict[str, Any]:
    info = path_stat(path, expect_dir=True)
    info["kind"] = kind

    if not info["exists"]:
        info["status"] = "missing_ok" if missing_ok else "incomplete_possible"
        info["reason"] = missing_reason
        return info

    entry_count = count_dir_entries(path)
    info["entry_count"] = entry_count
    if entry_count == 0:
        info["status"] = "incomplete_possible"
        info["reason"] = "directory exists but is empty."
    elif entry_count is None:
        info["status"] = "incomplete_possible"
        info["reason"] = "directory exists but entries could not be inspected."
    else:
        info["status"] = "present"
        info["reason"] = "directory exists and contains output files."
    return info


def derive_stage2_name(base_run_name: str) -> str | None:
    marker = "img960_musgd_e200"
    if marker not in base_run_name:
        return None
    return base_run_name.replace(marker, "img1280_ft80_nomosaic")


def strip_ansi(text: str) -> str:
    return ANSI_ESCAPE_RE.sub("", text)


def run_date_from_name(run_name: str) -> str | None:
    match = re.search(r"_(\d{8})$", run_name)
    return match.group(1) if match else None


def log_candidates(results_csv: Path) -> list[tuple[str, Path]]:
    run_name = results_csv.parent.name
    run_date = run_date_from_name(run_name)
    logs_dir = Path("logs")

    candidates: list[tuple[str, Path]] = []
    seen: set[Path] = set()

    def add(kind: str, path: Path) -> None:
        key = path
        if key not in seen:
            seen.add(key)
            candidates.append((kind, path))

    for path in sorted(
        logs_dir.glob("walksafe_tactile3_yolo26s_resume_pipeline_*.log"),
        key=lambda item: item.stat().st_mtime if item.exists() else 0,
        reverse=True,
    ):
        add("resume", path)
    if run_date:
        add("original", logs_dir / f"walksafe_tactile3_yolo26s_pipeline_{run_date}.log")
    for path in sorted(
        logs_dir.glob("walksafe_tactile3_yolo26s_pipeline_*.log"),
        key=lambda item: item.stat().st_mtime if item.exists() else 0,
        reverse=True,
    ):
        add("original", path)
    return candidates


def read_meaningful_tail(path: Path, limit: int) -> list[str]:
    if limit == 0:
        return []

    tail: deque[str] = deque(maxlen=limit)
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            for part in raw_line.splitlines():
                line = strip_ansi(part).strip()
                if line:
                    tail.append(line)
    return list(tail)


def collect_log_tail(results_csv: Path, limit: int) -> dict[str, Any]:
    candidates = log_candidates(results_csv)
    info: dict[str, Any] = {
        "requested_lines": limit,
        "status": "not_found",
        "source": None,
        "source_kind": None,
        "candidate_paths": [str(path) for _, path in candidates],
        "lines": [],
    }

    for kind, path in candidates:
        if not path.is_file():
            continue
        info["source"] = str(path)
        info["source_kind"] = kind
        try:
            info["lines"] = read_meaningful_tail(path, limit)
        except OSError as exc:
            info["status"] = "read_error"
            info["error"] = str(exc)
            return info

        info["status"] = "present"
        try:
            stat = path.stat()
        except OSError:
            return info
        info["mtime"] = file_time_iso(stat.st_mtime)
        info["mtime_unix"] = stat.st_mtime
        info["size_bytes"] = stat.st_size
        info["size_human"] = human_size(stat.st_size)
        return info

    return info


def collect_result_dirs(results_csv: Path) -> dict[str, dict[str, Any]]:
    return collect_result_dirs_with_context(results_csv, completed_epochs=None)


def collect_result_dirs_with_context(
    results_csv: Path, *, completed_epochs: int | None
) -> dict[str, dict[str, Any]]:
    run_dir = results_csv.parent
    detect_project = run_dir.parent
    runs_root = detect_project.parent
    validation_project = runs_root / "validation"
    run_name = run_dir.name

    stage1_train = assess_train_result_dir(
        run_dir,
        expected_epochs=EXPECTED_STAGE1_EPOCHS,
        known_completed_epochs=completed_epochs,
        missing_reason="stage1 train directory is not present.",
    )
    stage1_train_ready = stage1_train.get("status") == "present"

    stage1_test = assess_followup_result_dir(
        validation_project / f"{run_name}_test",
        kind="validation",
        missing_ok=not stage1_train_ready,
        missing_reason=(
            "missing is OK while stage1 training is not clearly complete."
            if not stage1_train_ready
            else "stage1 training appears complete, but stage1 test output is missing."
        ),
    )
    stage1_test_ready = stage1_test.get("status") == "present"

    result_dirs: dict[str, dict[str, Any]] = {
        "stage1_train": stage1_train,
        "stage1_test": stage1_test,
    }

    stage2_name = derive_stage2_name(run_name)
    if stage2_name is None:
        result_dirs["stage2_train"] = {
            "path": None,
            "exists": False,
            "status": "missing_ok",
            "kind": "train",
            "note": "stage2 run name is inferred only for the current img960_musgd_e200 run pattern.",
        }
        result_dirs["stage2_test"] = {
            "path": None,
            "exists": False,
            "status": "missing_ok",
            "kind": "validation",
            "note": "stage2 run name is inferred only for the current img960_musgd_e200 run pattern.",
        }
        return result_dirs

    stage2_train = assess_train_result_dir(
        detect_project / stage2_name,
        expected_epochs=EXPECTED_STAGE2_EPOCHS,
        missing_reason=(
            "missing is OK until stage1 train/test outputs are present."
            if not (stage1_train_ready and stage1_test_ready)
            else "stage1 outputs are present, but stage2 train output is missing."
        ),
    )
    if not stage2_train["exists"] and stage1_train_ready and stage1_test_ready:
        stage2_train["status"] = "incomplete_possible"
    stage2_train_ready = stage2_train.get("status") == "present"

    result_dirs["stage2_train"] = stage2_train
    result_dirs["stage2_test"] = assess_followup_result_dir(
        validation_project / f"{stage2_name}_test",
        kind="validation",
        missing_ok=not stage2_train_ready,
        missing_reason=(
            "missing is OK while stage2 training is not clearly complete."
            if not stage2_train_ready
            else "stage2 training appears complete, but stage2 test output is missing."
        ),
    )
    return result_dirs


def build_summary(
    results_csv: Path, *, include_log_tail: int | None = None
) -> tuple[dict[str, Any], int]:
    columns, rows, warnings, errors = read_results_csv(results_csv)
    run_dir = results_csv.parent

    if columns and "epoch" not in columns:
        warnings.append("Column 'epoch' is missing; completed_epochs uses parsed row count.")
    for column in (MAP50_95_COL, MAP50_COL):
        if columns and column not in columns:
            warnings.append(f"Column '{column}' is missing; best epoch for this metric is unavailable.")

    completed_epochs = len(rows)
    last_row = rows[-1] if rows else None
    best_map50_95 = select_best(rows, MAP50_95_COL) if MAP50_95_COL in columns else None
    best_map50 = select_best(rows, MAP50_COL) if MAP50_COL in columns else None
    top_map50_95 = select_top(rows, MAP50_95_COL) if MAP50_95_COL in columns else []

    status = "ok"
    if errors:
        status = "partial" if rows else "error"
    elif warnings:
        status = "partial"

    summary = {
        "schema_version": 1,
        "generated_at": now_iso(),
        "parser": "scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py",
        "cpu_only": True,
        "status": status,
        "results_csv": str(results_csv),
        "run_dir": str(run_dir),
        "completed_epochs": completed_epochs,
        "columns": columns,
        "last_epoch": row_summary(last_row, selected_by="last_row"),
        "best": {
            "map50_95": row_summary(best_map50_95, selected_by=MAP50_95_COL),
            "map50": row_summary(best_map50, selected_by=MAP50_COL),
        },
        "top": {
            "map50_95": top_map50_95,
        },
        "weights": {
            "best.pt": path_stat(run_dir / "weights" / "best.pt"),
            "last.pt": path_stat(run_dir / "weights" / "last.pt"),
        },
        "result_dirs": collect_result_dirs_with_context(
            results_csv, completed_epochs=completed_epochs
        ),
        "warnings": warnings,
        "errors": errors,
    }
    if include_log_tail is not None:
        summary["log_tail"] = collect_log_tail(results_csv, include_log_tail)
    return summary, 1 if errors else 0


def fmt(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            return "-"
        return f"{value:.6g}"
    return str(value).replace("|", "\\|")


def compact_metrics(metrics: Row | None) -> dict[str, Any]:
    if not metrics:
        return {}
    return {column: metrics.get(column) for column in KEY_METRIC_COLUMNS if column in metrics}


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


def render_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# WalkSafe tactile3 YOLO26s Run Summary",
        "",
        "> CPU-only results.csv/filesystem parser. No inference, evaluation, or GPU work is executed.",
        "",
        "## Run",
        "",
        "| Item | Value |",
        "| --- | --- |",
        f"| Status | {fmt(summary['status'])} |",
        f"| Generated at | {fmt(summary['generated_at'])} |",
        f"| Results CSV | `{fmt(summary['results_csv'])}` |",
        f"| Run dir | `{fmt(summary['run_dir'])}` |",
        f"| Completed epochs | {fmt(summary['completed_epochs'])} |",
        "",
        "## Key epoch metrics",
        "",
        "| Selection | epoch | mAP50-95(B) | mAP50(B) | precision(B) | recall(B) | val/box_loss | val/cls_loss | val/dfl_loss |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]

    for row in (
        metric_table_row("last", summary.get("last_epoch")),
        metric_table_row("best mAP50-95", summary.get("best", {}).get("map50_95")),
        metric_table_row("best mAP50", summary.get("best", {}).get("map50")),
    ):
        lines.append("| " + " | ".join(row) + " |")

    top_map50_95 = summary.get("top", {}).get("map50_95") or []
    if top_map50_95:
        lines.extend(
            [
                "",
                "## Top 5 epochs by mAP50-95(B)",
                "",
                "| Rank | epoch | mAP50-95(B) | mAP50(B) | precision(B) | recall(B) |",
                "| ---: | ---: | ---: | ---: | ---: | ---: |",
            ]
        )
        for row in top_map50_95:
            metrics = row.get("metrics", {})
            lines.append(
                "| "
                + " | ".join(
                    [
                        fmt(row.get("rank")),
                        fmt(metrics.get("epoch")),
                        fmt(metrics.get(MAP50_95_COL)),
                        fmt(metrics.get(MAP50_COL)),
                        fmt(metrics.get("metrics/precision(B)")),
                        fmt(metrics.get("metrics/recall(B)")),
                    ]
                )
                + " |"
            )

    last_metrics = compact_metrics(
        (summary.get("last_epoch") or {}).get("metrics") if summary.get("last_epoch") else None
    )
    if last_metrics:
        lines.extend(["", "## Last epoch detail", "", "| Metric | Value |", "| --- | ---: |"])
        for key, value in last_metrics.items():
            lines.append(f"| `{fmt(key)}` | {fmt(value)} |")

    lines.extend([
        "",
        "## Checkpoints",
        "",
        "| File | Exists | mtime | Size | Path |",
        "| --- | --- | --- | ---: | --- |",
    ])
    for name, info in summary.get("weights", {}).items():
        lines.append(
            "| "
            + " | ".join(
                [
                    fmt(name),
                    fmt(info.get("exists")),
                    fmt(info.get("mtime")),
                    fmt(info.get("size_human") or info.get("size_bytes")),
                    f"`{fmt(info.get('path'))}`",
                ]
            )
            + " |"
        )

    lines.extend([
        "",
        "## Downstream result directories",
        "",
        "Statuses: `present` = output directory looks available, "
        "`missing_ok` = missing is expected for the current pipeline point, "
        "`incomplete_possible` = output may be still running/interrupted/early-stopped.",
        "",
        "| Name | Kind | Status | Reason | Path |",
        "| --- | --- | --- | --- | --- |",
    ])
    for name, info in summary.get("result_dirs", {}).items():
        lines.append(
            f"| {fmt(name)} | {fmt(info.get('kind'))} | {fmt(info.get('status'))} | "
            f"{fmt(info.get('reason') or info.get('note'))} | `{fmt(info.get('path'))}` |"
        )

    log_tail = summary.get("log_tail")
    if log_tail:
        lines.extend(
            [
                "",
                "## Log tail",
                "",
                "| Item | Value |",
                "| --- | --- |",
                f"| Status | {fmt(log_tail.get('status'))} |",
                f"| Source kind | {fmt(log_tail.get('source_kind'))} |",
                f"| Source | `{fmt(log_tail.get('source'))}` |",
                f"| Requested lines | {fmt(log_tail.get('requested_lines'))} |",
                "",
            ]
        )
        if log_tail.get("lines"):
            lines.append("Last meaningful lines, with ANSI escapes stripped:")
            lines.append("")
            for line in log_tail["lines"]:
                lines.append(f"    {line}")
        elif log_tail.get("error"):
            lines.append(f"- {fmt(log_tail['error'])}")
        else:
            lines.append("- No log lines found.")

    if summary.get("warnings"):
        lines.extend(["", "## Warnings", ""])
        lines.extend(f"- {fmt(warning)}" for warning in summary["warnings"])
    if summary.get("errors"):
        lines.extend(["", "## Errors", ""])
        lines.extend(f"- {fmt(error)}" for error in summary["errors"])

    return "\n".join(lines) + "\n"


def write_text(path: Path, text: str) -> None:
    if path.parent != Path(""):
        path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main() -> int:
    args = parse_args()
    summary, exit_code = build_summary(
        args.results_csv, include_log_tail=args.include_log_tail
    )

    wrote_output = False
    if args.out_json:
        write_text(args.out_json, json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
        wrote_output = True
    if args.out_md:
        write_text(args.out_md, render_markdown(summary))
        wrote_output = True

    if not wrote_output:
        sys.stdout.write(render_markdown(summary))

    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
