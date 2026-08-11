#!/usr/bin/env python3
"""Compare a Gitleaks JSON report with an exact reviewed finding-set digest."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from typing import Any


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read JSON {path}: {exc}") from exc


def canonical_report_bytes(findings: Any) -> bytes:
    if not isinstance(findings, list) or not all(
        isinstance(finding, dict)
        and all(isinstance(key, str) for key in finding)
        for finding in findings
    ):
        raise ValueError("Gitleaks report must be a list of JSON objects")
    encoded = [
        json.dumps(
            finding,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        for finding in findings
    ]
    return ("[" + ",".join(sorted(encoded)) + "]\n").encode("utf-8")


def report_sha256(findings: Any) -> str:
    return hashlib.sha256(canonical_report_bytes(findings)).hexdigest()


def check_report(findings: Any, policy: Any) -> tuple[str, ...]:
    if not isinstance(policy, dict):
        return ("policy must be a JSON object",)
    if policy.get("schema_version") != "walksafe.gitleaks-reviewed-findings.v1":
        return ("policy schema_version is unsupported",)
    expected_count = policy.get("finding_count")
    expected_sha256 = policy.get("canonical_findings_sha256")
    if not isinstance(expected_count, int) or expected_count < 0:
        return ("policy finding_count is invalid",)
    if not isinstance(expected_sha256, str) or not re.fullmatch(
        r"[0-9a-f]{64}", expected_sha256
    ):
        return ("policy canonical_findings_sha256 is invalid",)
    try:
        observed_sha256 = report_sha256(findings)
    except ValueError as exc:
        return (str(exc),)
    errors: list[str] = []
    if len(findings) != expected_count:
        errors.append(
            f"finding count differs: expected {expected_count}, observed {len(findings)}"
        )
    if observed_sha256 != expected_sha256:
        errors.append(
            "canonical finding-set SHA-256 differs: "
            f"expected {expected_sha256}, observed {observed_sha256}"
        )
    return tuple(errors)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        findings = _load_json(args.report)
        policy = _load_json(args.policy)
    except ValueError as exc:
        print(f"ERROR: {exc}")
        return 1
    errors = check_report(findings, policy)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"PASS: exact reviewed Gitleaks finding set ({len(findings)} findings)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
