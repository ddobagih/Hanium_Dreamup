#!/usr/bin/env python3
"""Read-only inventory of high-confidence pytest report fixtures.

This command intentionally has no apply/delete mode. A candidate must use one
of the fixed test capture dates, reference a safe 4x4 image below the supplied
upload root, and bind that file with the stored image SHA-256.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from PIL import Image
from sqlalchemy import create_engine, text


FIXTURE_CAPTURE_DATES = frozenset({"2026-05-12", "2026-05-22"})


def _safe_fixture_image(upload_root: Path, image_path: str, expected_sha256: str) -> Path | None:
    if not image_path.startswith("/uploads/") or not expected_sha256:
        return None
    relative = image_path.removeprefix("/uploads/")
    if not relative or "/" in relative or relative in {".", ".."}:
        return None
    configured = upload_root / relative
    if configured.is_symlink():
        return None
    candidate = configured.resolve()
    if candidate.parent != upload_root or not candidate.is_file():
        return None
    digest = hashlib.sha256(candidate.read_bytes()).hexdigest()
    if digest != expected_sha256.lower():
        return None
    try:
        with Image.open(candidate) as image:
            if image.size != (4, 4):
                return None
    except OSError:
        return None
    return candidate


def audit(database_url: str, upload_dir: Path) -> dict[str, Any]:
    upload_root = upload_dir.expanduser().resolve()
    engine = create_engine(database_url, pool_pre_ping=True)
    statement = text(
        """
        SELECT id::text, (captured_at AT TIME ZONE 'UTC')::date::text AS captured_date, image_path,
               metadata->>'image_sha256' AS image_sha256
        FROM reports
        WHERE captured_at >= TIMESTAMPTZ '2026-05-12 00:00:00+00'
          AND captured_at < TIMESTAMPTZ '2026-05-23 00:00:00+00'
          AND (captured_at AT TIME ZONE 'UTC')::date IN (DATE '2026-05-12', DATE '2026-05-22')
        ORDER BY id
        """
    )
    candidates: list[dict[str, str]] = []
    with engine.connect() as connection:
        connection.execute(text("SET TRANSACTION READ ONLY"))
        database_name = str(connection.execute(text("SELECT current_database()")).scalar_one())
        for row in connection.execute(statement).mappings():
            if row["captured_date"] not in FIXTURE_CAPTURE_DATES:
                continue
            image = _safe_fixture_image(
                upload_root,
                str(row["image_path"] or ""),
                str(row["image_sha256"] or ""),
            )
            if image is None:
                continue
            candidates.append(
                {
                    "id": str(row["id"]),
                    "captured_date": str(row["captured_date"]),
                    "image_path": str(image),
                    "reason": "fixed_test_date+4x4_image+stored_sha256_match",
                }
            )
        connection.rollback()
    engine.dispose()
    return {
        "schema_version": "walksafe.test-contamination-audit.v1",
        "mode": "dry-run-only",
        "database_name": database_name,
        "upload_root": str(upload_root),
        "candidate_count": len(candidates),
        "candidate_ids": [candidate["id"] for candidate in candidates],
        "candidates": candidates,
        "apply_supported": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--upload-dir", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(args.database_url, args.upload_dir)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(f"{rendered}\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
