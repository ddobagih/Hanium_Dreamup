#!/usr/bin/env python3
"""Verify the external report tombstone gate for an isolated restore.

This command is read-only.  Its default mode prints the deterministic reapply
plan.  A restore that still contains deleted report data exits non-zero.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.services.report_restore_tombstones import (  # noqa: E402
    ReportRestoreTombstoneError,
    assert_report_restore_publishable,
    canonical_json_bytes,
    load_report_restore_reapply_receipt_bytes,
    load_restored_report_inventory,
    load_verified_report_tombstone_bundle,
    plan_report_restore_reapply,
    report_restore_publish_gate_bytes,
    report_restore_reapply_plan_bytes,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fail-closed external report tombstone restore gate (read-only)."
    )
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--ledger-signature", required=True, type=Path)
    parser.add_argument("--trusted-head", required=True, type=Path)
    parser.add_argument("--head-signature", required=True, type=Path)
    parser.add_argument("--trusted-key", required=True, type=Path)
    parser.add_argument("--expected-key-id", required=True)
    parser.add_argument("--expected-head-sha256", required=True)
    parser.add_argument("--inventory", required=True, type=Path)
    parser.add_argument("--expected-inventory-sha256", required=True)
    parser.add_argument(
        "--post-inventory",
        type=Path,
        help="Fresh complete inventory after a manual reapply.",
    )
    parser.add_argument(
        "--expected-post-inventory-sha256",
        help="Out-of-band integrity anchor for the fresh post-reapply inventory.",
    )
    parser.add_argument(
        "--reapply-receipt",
        type=Path,
        help="Exact receipt stored atomically with a manual reapply.",
    )
    return parser


def _error_payload(error: ReportRestoreTombstoneError) -> bytes:
    return canonical_json_bytes(
        {
            "code": error.code,
            "external_restore_status": "NOT_RUN",
            "publish_verdict": "BLOCKED",
            "schema_version": "walksafe.report-restore-tombstone-check.v1",
        }
    )


def main(argv: list[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    optional_post_inputs = (
        arguments.post_inventory,
        arguments.expected_post_inventory_sha256,
        arguments.reapply_receipt,
    )
    if any(value is not None for value in optional_post_inputs) and not all(
        value is not None for value in optional_post_inputs
    ):
        _parser().error(
            "--post-inventory, --expected-post-inventory-sha256, and "
            "--reapply-receipt must be supplied together"
        )
    try:
        bundle = load_verified_report_tombstone_bundle(
            ledger_path=arguments.ledger,
            ledger_signature_path=arguments.ledger_signature,
            trusted_head_path=arguments.trusted_head,
            head_signature_path=arguments.head_signature,
            key_descriptor_path=arguments.trusted_key,
            expected_key_id=arguments.expected_key_id,
            expected_head_sha256=arguments.expected_head_sha256,
        )
        before = load_restored_report_inventory(
            arguments.inventory,
            expected_inventory_sha256=arguments.expected_inventory_sha256,
        )
        if arguments.post_inventory is None:
            plan = plan_report_restore_reapply(bundle, before)
            sys.stdout.buffer.write(report_restore_reapply_plan_bytes(plan) + b"\n")
            return 2 if plan.actions else 0
        post = load_restored_report_inventory(
            arguments.post_inventory,
            expected_inventory_sha256=arguments.expected_post_inventory_sha256,
        )
        receipt = load_report_restore_reapply_receipt_bytes(arguments.reapply_receipt)
        gate = assert_report_restore_publishable(
            bundle,
            before_inventory=before,
            post_inventory=post,
            receipt_bytes=receipt,
        )
        sys.stdout.buffer.write(report_restore_publish_gate_bytes(gate) + b"\n")
        return 0
    except (OSError, ReportRestoreTombstoneError) as exc:
        error = (
            exc
            if isinstance(exc, ReportRestoreTombstoneError)
            else ReportRestoreTombstoneError(
                "restore_tombstone_evidence_unavailable",
                "Restore tombstone evidence is unavailable.",
            )
        )
        sys.stderr.buffer.write(_error_payload(error) + b"\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
