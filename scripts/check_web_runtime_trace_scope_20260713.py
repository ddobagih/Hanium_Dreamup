#!/usr/bin/env python3
"""Reject Next production traces that capture files outside reviewed runtime roots."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WEB_ROOT = ROOT / "apps/web"
FORBIDDEN_RUNTIME_PATH = re.compile(
    r"(?:^|/)(?:\.env(?:\..*)?|secrets?|field_sessions?|logs?|tests?|fixtures?|daylog)(?:/|$)|"
    r"\.(?:log|jsonl|sqlite|db|pem|key)$",
    re.IGNORECASE,
)


def audit_trace_scope(web_root: Path, build_dir: Path) -> tuple[int, int]:
    web_root = web_root.resolve()
    build_root = build_dir.resolve()
    try:
        build_root.relative_to(web_root)
    except ValueError as exc:
        raise ValueError("Next build directory must stay inside the Web project") from exc
    traces = sorted(build_root.rglob("*.nft.json"))
    if not traces:
        raise ValueError(f"no Next runtime trace files found under {build_root}")

    unique_files: set[Path] = set()
    unexpected: list[str] = []
    for trace in traces:
        payload = json.loads(trace.read_text(encoding="utf-8"))
        entries = payload.get("files")
        if not isinstance(entries, list) or not all(isinstance(entry, str) for entry in entries):
            raise ValueError(f"invalid files list in {trace}")
        for entry in entries:
            resolved = (trace.parent / entry).resolve()
            unique_files.add(resolved)
            if resolved.is_relative_to(web_root / "node_modules"):
                continue
            if resolved.is_relative_to(build_root):
                relative_runtime_path = resolved.relative_to(build_root).as_posix()
                if FORBIDDEN_RUNTIME_PATH.search(relative_runtime_path):
                    unexpected.append(f"{trace.relative_to(web_root)} -> {resolved}")
                continue
            if resolved == web_root / "package.json":
                continue
            unexpected.append(f"{trace.relative_to(web_root)} -> {resolved}")
    if unexpected:
        raise ValueError(f"Web runtime traces captured unreviewed files: {unexpected[:10]}")
    return len(traces), len(unique_files)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web-root", type=Path, default=DEFAULT_WEB_ROOT)
    parser.add_argument("--build-dir", type=Path, default=Path(".next"))
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    web_root = args.web_root.resolve()
    build_dir = args.build_dir if args.build_dir.is_absolute() else web_root / args.build_dir
    try:
        trace_count, file_count = audit_trace_scope(web_root, build_dir)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"Web runtime trace scope FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"Web runtime trace scope PASS: {trace_count} traces, {file_count} unique files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
