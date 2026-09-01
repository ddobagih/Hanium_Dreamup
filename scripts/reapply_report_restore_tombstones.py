#!/usr/bin/env python3
"""Plan or apply externally signed report tombstones to an isolated restore."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime
import os
from pathlib import Path
import signal
import sys
import uuid

from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.services import report_restore_tombstones as tombstones  # noqa: E402
from backend.app.services.report_restore_handoff import (  # noqa: E402
    held_report_restore_source_fence,
    load_stable_trusted_key,
    prepare_signed_evidence_paths,
    verify_stable_handoff,
    wait_for_stable_signed_evidence,
)
from backend.app.services.report_restore_postgres import (  # noqa: E402
    RestoreInventoryContext,
    isolated_postgres_report_restore_target,
)


SOURCE_DATABASE_ENV = "WALKSAFE_REPORT_RESTORE_SOURCE_DATABASE_URL"
TARGET_DATABASE_ENV = "WALKSAFE_REPORT_RESTORE_TARGET_DATABASE_URL"
SOURCE_UPLOAD_ENV = "WALKSAFE_REPORT_RESTORE_SOURCE_UPLOAD_DIR"
TARGET_UPLOAD_ENV = "WALKSAFE_REPORT_RESTORE_TARGET_UPLOAD_DIR"
MAINTENANCE_LOCK_ENV = "WALKSAFE_MAINTENANCE_LOCK_PATH"


def _timestamp(value: str) -> datetime:
    if not value.endswith("Z"):
        raise argparse.ArgumentTypeError("timestamp must be canonical UTC with Z")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise argparse.ArgumentTypeError("timestamp is invalid") from exc
    if parsed.tzinfo is None or parsed.astimezone(UTC) != parsed:
        raise argparse.ArgumentTypeError("timestamp must be UTC")
    canonical = parsed.strftime("%Y-%m-%dT%H:%M:%SZ")
    if parsed.microsecond:
        fraction = f"{parsed.microsecond:06d}".rstrip("0")
        canonical = parsed.strftime("%Y-%m-%dT%H:%M:%S") + f".{fraction}Z"
    if canonical != value:
        raise argparse.ArgumentTypeError("timestamp is not canonical")
    return parsed


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restore-run-id", required=True, type=uuid.UUID)
    parser.add_argument("--backup-run-id", required=True)
    parser.add_argument("--backup-manifest-sha256", required=True)
    parser.add_argument("--restore-receipt-sha256", required=True)
    parser.add_argument("--data-boundary-id", required=True)
    parser.add_argument("--privacy-hmac-key-version", required=True, type=int)
    parser.add_argument("--source-identity-sha256", required=True)
    parser.add_argument(
        "--source-backup-created-at",
        required=True,
        type=_timestamp,
    )
    parser.add_argument("--trusted-key", required=True, type=Path)
    parser.add_argument("--expected-key-id", required=True)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--ledger-signature", required=True, type=Path)
    parser.add_argument("--trusted-head", required=True, type=Path)
    parser.add_argument("--head-signature", required=True, type=Path)
    parser.add_argument("--fence-binding", required=True, type=Path)
    parser.add_argument("--fence-binding-signature", required=True, type=Path)
    parser.add_argument("--source-lock-timeout-seconds", type=int, default=30)
    parser.add_argument("--evidence-wait-seconds", type=int, default=30)
    parser.add_argument("--target-isolation-wait-seconds", type=int, default=30)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm")
    return parser


def _required_environment(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise tombstones.ReportRestoreTombstoneError(
            "restore_configuration_missing",
            "A required restore environment value is missing.",
        )
    return value


def _error_payload(code: str, *, target_mutation_status: str) -> bytes:
    return tombstones.canonical_json_bytes(
        {
            "code": code,
            "external_restore_status": "NOT_RUN",
            "publish_verdict": "BLOCKED",
            "schema_version": "walksafe.report-restore-reapply-run.v1",
            "target_mutation_status": target_mutation_status,
        }
    )


def _signal_interrupt(_signum: int, _frame: object) -> None:
    raise InterruptedError("restore reapply interrupted")


def main(argv: list[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    if arguments.apply and arguments.confirm != tombstones.APPLY_CONFIRMATION:
        _parser().error(
            f"--apply requires --confirm {tombstones.APPLY_CONFIRMATION}"
        )
    if not arguments.apply and arguments.confirm is not None:
        _parser().error("--confirm is accepted only with --apply")
    evidence_paths = {
        "ledger": arguments.ledger,
        "ledger_signature": arguments.ledger_signature,
        "trusted_head": arguments.trusted_head,
        "head_signature": arguments.head_signature,
        "fence_binding": arguments.fence_binding,
        "fence_binding_signature": arguments.fence_binding_signature,
    }
    previous_handlers = {
        signal_number: signal.getsignal(signal_number)
        for signal_number in (signal.SIGINT, signal.SIGTERM)
    }
    for signal_number in previous_handlers:
        signal.signal(signal_number, _signal_interrupt)
    target_mutation_status = "NOT_STARTED"
    try:
        source_database_url = _required_environment(SOURCE_DATABASE_ENV)
        target_database_url = _required_environment(TARGET_DATABASE_ENV)
        source_upload_root = Path(_required_environment(SOURCE_UPLOAD_ENV))
        target_upload_root = Path(_required_environment(TARGET_UPLOAD_ENV))
        maintenance_lock_path = Path(_required_environment(MAINTENANCE_LOCK_ENV))
        trusted_key = load_stable_trusted_key(arguments.trusted_key)
        with prepare_signed_evidence_paths(evidence_paths) as prepared:
            with held_report_restore_source_fence(
                source_database_url=source_database_url,
                maintenance_lock_path=maintenance_lock_path,
                restore_run_id=arguments.restore_run_id,
                backup_run_id=arguments.backup_run_id,
                backup_manifest_sha256=arguments.backup_manifest_sha256,
                source_identity_sha256=arguments.source_identity_sha256,
                data_boundary_id=arguments.data_boundary_id,
                lock_timeout_seconds=arguments.source_lock_timeout_seconds,
            ) as held_source:
                prepared.verify_absent_unchanged()
                sys.stdout.buffer.write(
                    tombstones.report_restore_source_fence_request_bytes(
                        held_source.request
                    )
                    + b"\n"
                )
                sys.stdout.buffer.flush()
                with wait_for_stable_signed_evidence(
                    evidence_paths,
                    timeout_seconds=arguments.evidence_wait_seconds,
                    verify_source_fence=held_source.verify_request_held,
                    prepared=prepared,
                ) as evidence:
                    bundle, source_fence = verify_stable_handoff(
                        held_source=held_source,
                        evidence=evidence,
                        trusted_key_bytes=trusted_key,
                        expected_key_id=arguments.expected_key_id,
                    )
                    inventory_context = RestoreInventoryContext(
                        restore_run_id=arguments.restore_run_id,
                        backup_run_id=arguments.backup_run_id,
                        backup_manifest_sha256=(
                            arguments.backup_manifest_sha256
                        ),
                        restore_receipt_sha256=(
                            arguments.restore_receipt_sha256
                        ),
                        data_boundary_id=arguments.data_boundary_id,
                        privacy_hmac_key_version=(
                            arguments.privacy_hmac_key_version
                        ),
                        source_identity_sha256=(
                            arguments.source_identity_sha256
                        ),
                        source_backup_created_at=(
                            arguments.source_backup_created_at
                        ),
                    )

                    def verify_mutation_boundary() -> None:
                        held_source.verify_request_held()
                        evidence.verify_unchanged()

                    def notify_target_admission_gate(runtime, backend_pid: int) -> None:
                        sys.stdout.buffer.write(
                            tombstones.canonical_json_bytes(
                                {
                                    "database_name": runtime.database_name,
                                    "database_oid": runtime.database_oid,
                                    "required_database_state": {
                                        "allow_connections": False,
                                        "other_session_count": 0,
                                    },
                                    "schema_version": (
                                        "walksafe.report-restore-target-admission-request.v1"
                                    ),
                                    "system_identifier": runtime.system_identifier,
                                    "worker_backend_pid": backend_pid,
                                }
                            )
                            + b"\n"
                        )
                        sys.stdout.buffer.flush()

                    with isolated_postgres_report_restore_target(
                        target_database_url=target_database_url,
                        source_runtime_identity=held_source.runtime_identity,
                        source_upload_root=source_upload_root,
                        target_upload_root=target_upload_root,
                        inventory_context=inventory_context,
                        mutation_guard=verify_mutation_boundary,
                        target_admission_notifier=notify_target_admission_gate,
                        target_admission_timeout_seconds=(
                            arguments.target_isolation_wait_seconds
                        ),
                    ) as target:
                        held_source.verify_request_held()
                        evidence.verify_unchanged()
                        before = target.build_inventory(reconcile=arguments.apply)
                        plan = tombstones.plan_report_restore_reapply(
                            bundle,
                            before,
                            source_fence=source_fence,
                        )
                        sys.stdout.buffer.write(
                            tombstones.report_restore_reapply_plan_bytes(plan)
                            + b"\n"
                        )
                        sys.stdout.buffer.flush()
                        if not arguments.apply:
                            return 2 if plan.actions else 0
                        applied_at = target.connection.execute(
                            text("SELECT clock_timestamp()")
                        ).scalar_one()
                        target.connection.rollback()
                        try:
                            outcome = tombstones.execute_report_restore_reapply(
                                plan,
                                target,
                                dry_run=False,
                                confirmation=arguments.confirm,
                                applied_at=applied_at,
                                source_fence_verifier=held_source,
                            )
                        finally:
                            target_mutation_status = target.mutation_state
                        if target.reconciliation_error is not None:
                            raise target.reconciliation_error
                        held_source.verify_request_held()
                        evidence.verify_unchanged()
                        post = target.build_inventory(reconcile=True)
                        receipt_bytes = (
                            tombstones.report_restore_reapply_receipt_bytes(
                                outcome.receipt
                            )
                            if outcome.receipt is not None
                            else None
                        )
                        gate = tombstones.assert_report_restore_publishable(
                            bundle,
                            before_inventory=before,
                            post_inventory=post,
                            receipt_bytes=receipt_bytes,
                            source_fence=source_fence,
                            source_fence_verifier=held_source,
                        )
                        target_mutation_status = target.mutation_state
                        evidence.verify_unchanged()
                        sys.stdout.buffer.write(
                            tombstones.report_restore_publish_gate_bytes(gate)
                            + b"\n"
                        )
                        sys.stdout.buffer.flush()
                        return 0
    except (
        InterruptedError,
        KeyboardInterrupt,
        OSError,
        RuntimeError,
        SQLAlchemyError,
        ValueError,
        tombstones.ReportRestoreTombstoneError,
    ) as exc:
        code = (
            exc.code
            if isinstance(exc, tombstones.ReportRestoreTombstoneError)
            else "restore_reapply_not_run"
        )
        sys.stderr.buffer.write(
            _error_payload(
                code,
                target_mutation_status=target_mutation_status,
            )
            + b"\n"
        )
        sys.stderr.buffer.flush()
        return 2
    finally:
        for signal_number, handler in previous_handlers.items():
            signal.signal(signal_number, handler)


if __name__ == "__main__":
    raise SystemExit(main())
