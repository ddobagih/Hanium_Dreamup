#!/usr/bin/env python3
"""Validate the isolated, non-effective WalkSafe v2.5 Goal candidate."""

from __future__ import annotations

import argparse
import importlib
from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

WRAPPER_PATH = Path(__file__)
CANDIDATE_FILENAME = "check_walksafe_goal_graph_v2_5_candidate.py"
ACTIVE_FILENAME = "check_walksafe_goal_graph_v2_5.py"
if WRAPPER_PATH.is_symlink() or WRAPPER_PATH.name not in {
    CANDIDATE_FILENAME,
    ACTIVE_FILENAME,
}:
    raise RuntimeError("v2.5 Goal wrapper path is not exact/regular")
IS_CANDIDATE_PATH = WRAPPER_PATH.name == CANDIDATE_FILENAME
CORE_MODULE = (
    "scripts.walksafe_v2_5_candidate_validation"
    if IS_CANDIDATE_PATH
    else "scripts.walksafe_v2_5_validation"
)
validation = importlib.import_module(CORE_MODULE)


def validate(root: Path = REPO_ROOT, *, mode: str | None = None) -> None:
    selected = mode or ("CANDIDATE" if IS_CANDIDATE_PATH else "ACTIVE")
    if IS_CANDIDATE_PATH and selected == "CANDIDATE":
        validation.validate_candidate_bundle(root, mode="CANDIDATE")
        return
    if IS_CANDIDATE_PATH and selected == "QUICK_PRECHECK":
        validation.validate_quick_precheck(
            root,
            check_id="GOAL_GRAPH_QUICK_V2_5",
        )
        return
    if not IS_CANDIDATE_PATH and selected == "ACTIVE":
        validation.validate_active_files(
            root,
            check_id="GOAL_GRAPH_QUICK_V2_5",
        )
        return
    raise validation.ValidationError(
        f"wrapper path/mode mismatch: candidate={IS_CANDIDATE_PATH} mode={selected}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    parser.add_argument(
        "--mode",
        choices=("CANDIDATE", "QUICK_PRECHECK", "ACTIVE"),
        default=None,
    )
    args = parser.parse_args()
    try:
        validate(args.root.resolve(strict=True), mode=args.mode)
    except (OSError, TypeError, ValueError) as exc:
        print(f"WalkSafe v2.5 candidate Goal graph: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "WalkSafe v2.5 Goal graph: PASS "
        f"mode={args.mode or ('CANDIDATE' if IS_CANDIDATE_PATH else 'ACTIVE')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
