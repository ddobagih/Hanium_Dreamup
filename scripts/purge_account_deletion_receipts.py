#!/usr/bin/env python3
"""S1617-56: manual one-shot purge of expired account deletion receipts.

Run as a dedicated login that is a member of ``walksafe_receipt_purger``
(never the runtime, worker or owner role). The database function deletes only
receipts whose three-year expiry has passed; deletion tombstones and the
request ledger stay, so old requests of a deleted generation remain blocked.
There is no service or timer: scheduling is an operations decision.

Environment: WALKSAFE_RECEIPT_PURGE_DATABASE_URL (not printed).
Output: one JSON line {"schema_version", "deleted", "batches", "exhausted"}.
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

from backend.app.services.account_deletion_receipt_purge import (  # noqa: E402
    MAX_BATCHES,
    MAX_BATCH_LIMIT,
    ReceiptPurgeError,
    purge_expired_account_deletion_receipts,
)

DATABASE_URL_ENV = "WALKSAFE_RECEIPT_PURGE_DATABASE_URL"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--batch-limit", type=int, default=1_000)
    parser.add_argument("--max-batches", type=int, default=100)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    if not 1 <= args.batch_limit <= MAX_BATCH_LIMIT or not 1 <= args.max_batches <= MAX_BATCHES:
        print(json.dumps({"error": "batch limits are out of range"}), file=sys.stderr)
        return 2
    url = os.environ.get(DATABASE_URL_ENV, "").strip()
    if not url:
        print(json.dumps({"error": f"{DATABASE_URL_ENV} is required"}), file=sys.stderr)
        return 2
    engine = create_engine(url, pool_pre_ping=True)
    try:
        with sessionmaker(bind=engine, expire_on_commit=False)() as db:
            result = purge_expired_account_deletion_receipts(
                db,
                batch_limit=args.batch_limit,
                max_batches=args.max_batches,
            )
    except ReceiptPurgeError as exc:
        print(json.dumps({"error": str(exc)}), file=sys.stderr)
        return 1
    except SQLAlchemyError as exc:
        # Never echo the connection string or driver detail.
        print(
            json.dumps({"error": "database purge failed", "type": type(exc).__name__}),
            file=sys.stderr,
        )
        return 1
    finally:
        engine.dispose()
    print(json.dumps(result.as_dict(), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
