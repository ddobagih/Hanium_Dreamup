#!/usr/bin/env python3
"""Summarize one runner invocation; a report alone is not execution success."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET


def summarize(directory: Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    scope = directory / "scope.tsv"
    if not scope.is_file():
        return rows
    for line in scope.read_text(encoding="utf-8").splitlines():
        group, selection, paths = line.split("\t", 2)
        row: dict[str, object] = {
            "group": group, "selection": selection, "selected_files": paths.split(),
            "status": "EXCLUDED" if selection == "excluded" else "NOT_RUN",
            "tests": 0, "failures": 0, "errors": 0, "skipped": 0,
        }
        started = directory / f"{group}.started"
        exit_path = directory / f"{group}.exit"
        report = directory / f"{group}.xml"
        row["report_present"] = report.is_file()
        if selection == "selected" and started.is_file():
            row["status"] = "INCOMPLETE"
            try:
                exit_code = int(exit_path.read_text()) if exit_path.is_file() else None
                row["exit_code"] = exit_code
                if report.is_file():
                    document = ET.parse(report).getroot()
                    cases = list(document.iter("testcase"))
                    row.update(
                        tests=len(cases),
                        failures=sum(case.find("failure") is not None for case in cases),
                        errors=sum(case.find("error") is not None for case in cases),
                        skipped=sum(case.find("skipped") is not None for case in cases),
                    )
                    if exit_code is not None:
                        row["status"] = "PASS" if exit_code == 0 and cases and not row["failures"] and not row["errors"] else "FAIL"
                elif exit_code is not None and exit_code != 0:
                    row["status"] = "FAIL"
            except (OSError, ValueError, ET.ParseError) as exc:
                row.update(status="INVALID_REPORT", report_error=str(exc))
        inventory = directory / f"{group}-inventory.json"
        if inventory.is_file():
            row["selected_files"] = json.loads(inventory.read_text())["files"]
        rows.append(row)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report-dir", required=True, type=Path)
    parser.add_argument("--exit-code", required=True, type=int)
    args = parser.parse_args()
    rows = summarize(args.report_dir)
    result = {"runner_exit_code": args.exit_code, "groups": rows}
    (args.report_dir / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    lines = ["## Test layers: " + args.report_dir.name, "", "| Group | Status | Tests | Failures | Errors | Skipped | Selected files |", "| --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for row in rows:
        lines.append(f"| {row['group']} | {row['status']} | {row['tests']} | {row['failures']} | {row['errors']} | {row['skipped']} | {len(row['selected_files'])} |")
    lines.extend(["", "EXCLUDED is outside this selector. NOT_RUN is selected but not started; neither is PASS.", "Historical control tests stay excluded from product selectors. Web policy checks, Android build/lint/JVM and model-smoke results are not counted as Python/Node testcases here.", ""])
    markdown = "\n".join(lines)
    (args.report_dir / "summary.md").write_text(markdown)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as handle:
            handle.write(markdown)
    print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
