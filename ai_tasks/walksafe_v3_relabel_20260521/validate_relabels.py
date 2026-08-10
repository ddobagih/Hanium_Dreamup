#!/usr/bin/env python3
"""Validate labels_final for the WalkSafe v3 relabel task package."""

from __future__ import annotations

import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
IMAGES = ROOT / "images"
LABELS_FINAL = ROOT / "labels_final"
EXCLUDE_CSV = ROOT / "exclude_candidates.csv"


def load_excluded_stems() -> set[str]:
    if not EXCLUDE_CSV.exists():
        return set()
    with EXCLUDE_CSV.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    stems: set[str] = set()
    for row in rows:
        image_id = (row.get("image_id") or "").strip()
        if image_id:
            stems.add(Path(image_id).stem)
    return stems


def main() -> int:
    errors: list[str] = []
    image_stems = {path.stem for path in IMAGES.glob("*.jpg")}
    excluded_stems = load_excluded_stems()
    label_paths = sorted(LABELS_FINAL.glob("*.txt"))
    label_stems = {path.stem for path in label_paths}

    missing = sorted(image_stems - label_stems - excluded_stems)
    extra = sorted(label_stems - image_stems)
    if missing:
        errors.append("missing labels or exclude rows for images: " + ", ".join(missing))
    if extra:
        errors.append("label files without matching image: " + ", ".join(extra))

    for path in label_paths:
        for line_no, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            line = raw_line.strip()
            if not line:
                continue
            parts = line.split()
            if len(parts) != 5:
                errors.append(f"{path}:{line_no}: expected 5 fields, got {len(parts)}")
                continue
            if parts[0] != "0":
                errors.append(f"{path}:{line_no}: class id must be 0, got {parts[0]}")
            try:
                x, y, w, h = [float(value) for value in parts[1:]]
            except ValueError:
                errors.append(f"{path}:{line_no}: coordinates must be numeric")
                continue
            if not all(0.0 <= value <= 1.0 for value in (x, y, w, h)):
                errors.append(f"{path}:{line_no}: coordinates outside [0, 1]")
            if w <= 0.0 or h <= 0.0:
                errors.append(f"{path}:{line_no}: width/height must be positive")

    print(f"images={len(image_stems)} labels_final={len(label_stems)} excluded={len(excluded_stems)}")
    if errors:
        print("errors:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("validation: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
