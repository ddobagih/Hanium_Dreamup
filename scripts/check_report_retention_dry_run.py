#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import UTC, datetime
import json
from pathlib import Path
from typing import Any


RETENTION_DAYS = {
    "fake_demo": 30,
    "active": 180,
    "resolved": 180,
}


def parse_datetime(value: str) -> datetime:
    normalized = value.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def is_fake_demo(row: dict[str, Any]) -> bool:
    metadata = row.get("metadata")
    if not isinstance(metadata, dict):
        metadata = row.get("payload") if isinstance(row.get("payload"), dict) else {}
    review_flags = row.get("review_flags")
    if not isinstance(review_flags, list):
        review_flags = metadata.get("review_flags") if isinstance(metadata.get("review_flags"), list) else []
    source_model = metadata.get("source_model")

    return (
        row.get("source") == "fake"
        or metadata.get("fake_source") is True
        or metadata.get("data_origin") == "demo"
        or metadata.get("performance_excluded") is True
        or "fake_source" in review_flags
        or (isinstance(source_model, str) and ("fake" in source_model.lower() or "demo" in source_model.lower()))
    )


def retention_bucket(row: dict[str, Any]) -> tuple[str, int]:
    if is_fake_demo(row):
        return "fake_demo", RETENTION_DAYS["fake_demo"]
    if row.get("status") == "resolved":
        return "resolved", RETENTION_DAYS["resolved"]
    return "active", RETENTION_DAYS["active"]


def candidate_for_row(row: dict[str, Any], *, as_of: datetime) -> dict[str, Any] | None:
    created_at_value = row.get("created_at") or row.get("captured_at")
    if not isinstance(created_at_value, str):
        return None

    created_at = parse_datetime(created_at_value)
    age_days = (as_of - created_at).days
    bucket, retention_days = retention_bucket(row)
    if age_days < retention_days:
        return None

    return {
        "id": str(row.get("id", "")),
        "status": row.get("status"),
        "source": row.get("source"),
        "created_at": created_at.isoformat().replace("+00:00", "Z"),
        "age_days": age_days,
        "retention_days": retention_days,
        "reason": bucket,
        "image_path_present": bool(row.get("image_path")),
        "would_delete": False,
    }


def load_rows(path: Path | None) -> list[dict[str, Any]]:
    if path is None:
        return []
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload.get("rows") if isinstance(payload, dict) else payload
    if not isinstance(rows, list):
        raise ValueError("input JSON must be a list or an object with a rows list")
    return [row for row in rows if isinstance(row, dict)]


def write_markdown(path: Path, *, as_of: datetime, candidates: list[dict[str, Any]]) -> None:
    lines = [
        "# Report retention dry-run",
        "",
        f"- as_of: `{as_of.isoformat().replace('+00:00', 'Z')}`",
        f"- candidates: {len(candidates)}",
        "- destructive_action: false",
        "",
        "| id | status | source | age_days | retention_days | reason | image_path_present |",
        "|---|---|---|---:|---:|---|---|",
    ]
    for item in candidates:
        lines.append(
            "| {id} | {status} | {source} | {age_days} | {retention_days} | {reason} | {image_path_present} |".format(
                **item
            )
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Dry-run report retention candidate checker. Never deletes data.")
    parser.add_argument("--input-json", type=Path, help="Optional JSON fixture/list of report-like rows.")
    parser.add_argument("--as-of", default=datetime.now(UTC).isoformat().replace("+00:00", "Z"))
    parser.add_argument("--output-md", type=Path, help="Optional markdown artifact path.")
    parser.add_argument(
        "--execute-delete",
        action="store_true",
        help="Rejected safety valve. This script is intentionally dry-run only.",
    )
    args = parser.parse_args()

    if args.execute_delete:
        print("BLOCKED: this checker is dry-run only and will not delete rows or files.")
        return 2

    as_of = parse_datetime(args.as_of)
    rows = load_rows(args.input_json)
    candidates = [candidate for row in rows if (candidate := candidate_for_row(row, as_of=as_of)) is not None]
    result = {
        "schema_version": "walksafe.report_retention_dry_run.v1",
        "as_of": as_of.isoformat().replace("+00:00", "Z"),
        "input_rows": len(rows),
        "candidate_count": len(candidates),
        "destructive_action": False,
        "candidates": candidates,
    }
    if args.output_md:
        write_markdown(args.output_md, as_of=as_of, candidates=candidates)
        result["markdown_artifact"] = str(args.output_md)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
