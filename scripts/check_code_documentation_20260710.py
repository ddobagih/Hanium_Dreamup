#!/usr/bin/env python3
"""Check README coverage and comments in long first-party runtime modules."""

from __future__ import annotations

import json
import re
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]

# Namespace-only parents and single Next.js route leaves are documented by the
# nearest responsibility boundary instead of receiving duplicate README files.
REQUIRED_READMES = (
    "apps/README.md",
    "apps/android/README.md",
    "apps/android/adminapp/README.md",
    "apps/android/app/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/debuglog/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/README.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/README.md",
    "apps/android/app/src/test/README.md",
    "apps/web/README.md",
    "apps/web/app/README.md",
    "apps/web/app/admin/README.md",
    "apps/web/app/api/README.md",
    "apps/web/app/api/walksafe-test-log/README.md",
    "apps/web/app/_walksafe/README.md",
    "apps/web/app/_walksafe/components/README.md",
    "apps/web/app/_walksafe/hooks/README.md",
    "apps/web/lib/README.md",
    "apps/web/public/README.md",
    "apps/web/tests/README.md",
    "apps/web/types/README.md",
    "backend/README.md",
    "backend/alembic/README.md",
    "backend/app/README.md",
    "backend/app/api/README.md",
    "backend/app/services/README.md",
    "backend/tests/README.md",
    "voice/README.md",
    "model/README.md",
    "model/artifacts/README.md",
    "data_sources/README.md",
    "data_sources/scripts/README.md",
    "scripts/README.md",
    "tests/README.md",
    "configs/README.md",
)

RUNTIME_SOURCE_ROOTS = (
    "apps/web/app",
    "apps/web/lib",
    "apps/android/app/src/main/java",
    "backend/app",
    "voice",
    "model",
)
RUNTIME_SUFFIXES = {".ts", ".tsx", ".js", ".jsx", ".kt", ".py"}
LONG_MODULE_MIN_NONEMPTY_LINES = 80
EXCLUDED_PARTS = {"build", ".next", "node_modules", "tests", "test", "artifacts", "__pycache__"}


def runtime_modules() -> list[Path]:
    modules: list[Path] = []
    for root in RUNTIME_SOURCE_ROOTS:
        for path in (REPO_ROOT / root).rglob("*"):
            if not path.is_file() or path.suffix not in RUNTIME_SUFFIXES:
                continue
            if EXCLUDED_PARTS.intersection(path.relative_to(REPO_ROOT).parts):
                continue
            if path.name.endswith((".test.ts", ".test.tsx")) or path.name.startswith("test_"):
                continue
            text = path.read_text(encoding="utf-8")
            if sum(1 for line in text.splitlines() if line.strip()) >= LONG_MODULE_MIN_NONEMPTY_LINES:
                modules.append(path)
    return sorted(modules)


def has_explanatory_comment(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".py":
        if re.search(r'(^|\n)\s*(?:[rubfRUBF]*)?(?:"""|\'\'\')', text):
            return True
        return any(
            line.lstrip().startswith("# ")
            and not line.lstrip().startswith(("# noqa", "# pragma", "# type:"))
            for line in text.splitlines()
        )
    if re.search(r"/\*\*[\s\S]*?\*/", text):
        return True
    return any(
        line.lstrip().startswith("// ")
        and not line.lstrip().startswith(("// eslint", "// @"))
        for line in text.splitlines()
    )


def main() -> int:
    missing: list[str] = []
    empty: list[str] = []
    for relative_path in REQUIRED_READMES:
        path = REPO_ROOT / relative_path
        if not path.is_file():
            missing.append(relative_path)
        elif not path.read_text(encoding="utf-8").strip():
            empty.append(relative_path)

    long_modules = runtime_modules()
    missing_comments = [
        path.relative_to(REPO_ROOT).as_posix()
        for path in long_modules
        if not has_explanatory_comment(path)
    ]

    result = {
        "ok": not missing and not empty and not missing_comments,
        "required": len(REQUIRED_READMES),
        "missing": missing,
        "empty": empty,
        "long_modules": len(long_modules),
        "missing_comments": missing_comments,
    }
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
