#!/usr/bin/env python3
"""S1617-57: export, plan or reapply signed account deletion tombstones.

export  (source DB, read-only)  -> canonical hash-chained ledger + Ed25519 signature
plan    (restored DB, read-only) -> tombstones missing after a backup restore
apply   (restored DB, one transaction, owner connection) -> insert the missing ones

Keys are raw 32-byte Ed25519 files kept outside the database; the private key
never leaves the export host. Apply requires --confirm
REAPPLY-RESTORED-ACCOUNT-TOMBSTONES and refuses a restored store whose privacy
HMAC key binding differs from the ledger (recover the key first) or whose
tombstones conflict with the ledger. Output files are created exclusively
(mode 0600); database URLs are read from the environment and never printed.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import sessionmaker

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.services import account_tombstone_ledger as ledger_service  # noqa: E402

SOURCE_DATABASE_ENV = "WALKSAFE_ACCOUNT_TOMBSTONE_SOURCE_DATABASE_URL"
TARGET_DATABASE_ENV = "WALKSAFE_ACCOUNT_TOMBSTONE_TARGET_DATABASE_URL"
_MAX_FILE_BYTES = 64 * 1024 * 1024


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export")
    export.add_argument("--private-key", required=True, type=Path)
    export.add_argument("--ledger", required=True, type=Path)
    export.add_argument("--signature", required=True, type=Path)
    for name in ("plan", "apply"):
        command = commands.add_parser(name)
        command.add_argument("--trusted-key", required=True, type=Path)
        command.add_argument("--ledger", required=True, type=Path)
        command.add_argument("--signature", required=True, type=Path)
        if name == "apply":
            command.add_argument("--confirm", required=True)
    return parser


def _read(path: Path) -> bytes:
    metadata = path.lstat()
    if path.is_symlink() or not path.is_file() or metadata.st_size > _MAX_FILE_BYTES:
        raise ledger_service.AccountTombstoneLedgerError(
            "account_tombstone_ledger_input_unsafe", f"{path.name} is not a regular file"
        )
    return path.read_bytes()


def _write_new(path: Path, data: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())


def _session(env_name: str):
    url = os.environ.get(env_name, "").strip()
    if not url:
        raise ledger_service.AccountTombstoneLedgerError(
            "account_tombstone_database_missing", f"{env_name} is required"
        )
    engine = create_engine(url, pool_pre_ping=True)
    return engine, sessionmaker(bind=engine, expire_on_commit=False)()


def _plan_summary(plan: ledger_service.AccountTombstoneReapplyPlan, *, applied: bool) -> dict:
    return {
        "applied": applied,
        "ledger_sha256": plan.ledger_sha256,
        "missing": len(plan.missing),
        "present": plan.present,
        "schema_version": "walksafe.account-deletion-tombstone-reapply-result.v1",
    }


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    engine = None
    try:
        if args.command == "export":
            private_key = _read(args.private_key)
            engine, db = _session(SOURCE_DATABASE_ENV)
            with db:
                ledger, signature = ledger_service.export_account_tombstone_ledger(
                    db, private_key=private_key
                )
            _write_new(args.ledger, ledger)
            _write_new(args.signature, signature)
            document = json.loads(ledger)
            print(json.dumps({
                "entry_count": document["entry_count"],
                "head_sha256": document["head_sha256"],
                "schema_version": "walksafe.account-deletion-tombstone-export-result.v1",
            }, sort_keys=True))
            return 0
        verified = ledger_service.verify_account_tombstone_ledger(
            _read(args.ledger),
            _read(args.signature),
            trusted_public_key=_read(args.trusted_key),
        )
        engine, db = _session(TARGET_DATABASE_ENV)
        with db:
            if args.command == "plan":
                plan = ledger_service.plan_account_tombstone_reapply(db, verified)
                print(json.dumps(_plan_summary(plan, applied=False), sort_keys=True))
                return 0
            plan = ledger_service.apply_account_tombstone_reapply(
                db, verified, confirmation=args.confirm
            )
        print(json.dumps(_plan_summary(plan, applied=True), sort_keys=True))
        return 0
    except ledger_service.AccountTombstoneLedgerError as exc:
        print(json.dumps({"error": exc.code}), file=sys.stderr)
        return 1
    except (OSError, ValueError) as exc:
        print(json.dumps({"error": "input_invalid", "type": type(exc).__name__}), file=sys.stderr)
        return 1
    except SQLAlchemyError as exc:
        print(json.dumps({"error": "database_failed", "type": type(exc).__name__}), file=sys.stderr)
        return 1
    finally:
        if engine is not None:
            engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
