#!/usr/bin/env python3
"""Preview or manually purge bounded expired account-enrollment envelopes."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sys
import uuid

from sqlalchemy import and_, create_engine, delete, or_, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.config import _validate_deployment_database_transport  # noqa: E402
from backend.app.models import AccountEnrollment  # noqa: E402


MAX_BATCH_SIZE = 1_000
CONFIRMATION = "PURGE-EXPIRED-ACCOUNT-ENROLLMENTS"
TERMINAL_STATES = ("DELIVERY_FAILED", "EXPIRED", "EXHAUSTED", "CONSUMED")


class AccountEnrollmentPurgeError(RuntimeError):
    pass


def _parse_before(value: str) -> datetime:
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=UTC)
    except ValueError as exc:
        raise AccountEnrollmentPurgeError(
            "--before must be canonical whole-second UTC"
        ) from exc
    return parsed


def _candidate_ids(
    db: Session,
    *,
    before: datetime,
    limit: int,
    lock: bool,
) -> list[uuid.UUID]:
    if before.tzinfo is None or before.utcoffset() is None:
        raise AccountEnrollmentPurgeError("purge cutoff must include a timezone")
    if not 1 <= limit <= MAX_BATCH_SIZE:
        raise AccountEnrollmentPurgeError("purge limit must be between 1 and 1000")
    eligibility = or_(
        AccountEnrollment.expires_at <= before,
        and_(
            AccountEnrollment.state.in_(TERMINAL_STATES),
            AccountEnrollment.updated_at <= before,
        ),
    )
    statement = (
        select(AccountEnrollment.id)
        .where(eligibility)
        .order_by(AccountEnrollment.updated_at, AccountEnrollment.id)
        .limit(limit)
    )
    del lock
    return list(db.scalars(statement).all())


def _candidate_digest(candidate_ids: list[uuid.UUID]) -> str:
    return hashlib.sha256(
        b"walksafe/account-enrollment-purge-candidates/v1\0"
        + json.dumps(
            [str(value) for value in candidate_ids],
            ensure_ascii=True,
            separators=(",", ":"),
        ).encode("ascii")
    ).hexdigest()


def _validated_database_url(raw: str, *, deployment: bool) -> str:
    value = raw.strip()
    if not value:
        raise AccountEnrollmentPurgeError(
            "WALKSAFE_ACCOUNT_ENROLLMENT_PURGE_DATABASE_URL is required"
        )
    try:
        url = make_url(value)
    except Exception as exc:
        raise AccountEnrollmentPurgeError("purge database URL is invalid") from exc
    if url.drivername != "postgresql+psycopg" or not url.database or not url.username:
        raise AccountEnrollmentPurgeError(
            "purge database URL must use postgresql+psycopg"
        )
    if deployment:
        try:
            _validate_deployment_database_transport(value)
        except ValueError as exc:
            raise AccountEnrollmentPurgeError(
                "purge database transport is unsafe"
            ) from exc
    elif (url.host or "") not in {"127.0.0.1", "localhost", "::1"}:
        raise AccountEnrollmentPurgeError(
            "local isolated purge database must use loopback"
        )
    return value


def _assert_manual_role(db: Session) -> None:
    safe = bool(
        db.execute(
            text(
                "SELECT current_user = session_user "
                "AND NOT pg_has_role(current_user, 'walksafe_backend_runtime', 'USAGE') "
                "AND pg_has_role(current_user, "
                "'walksafe_account_enrollment_purger', 'USAGE') "
                "AND has_column_privilege(current_user, "
                "'public.account_enrollments', 'id', 'SELECT') "
                "AND has_column_privilege(current_user, "
                "'public.account_enrollments', 'state', 'SELECT') "
                "AND has_column_privilege(current_user, "
                "'public.account_enrollments', 'expires_at', 'SELECT') "
                "AND has_column_privilege(current_user, "
                "'public.account_enrollments', 'updated_at', 'SELECT') "
                "AND has_table_privilege(current_user, "
                "'public.account_enrollments', 'DELETE') "
                "AND NOT has_table_privilege(current_user, "
                "'public.account_enrollments', 'INSERT') "
                "AND NOT has_table_privilege(current_user, "
                "'public.account_enrollments', 'UPDATE') "
                "AND NOT has_table_privilege(current_user, "
                "'public.account_enrollments', 'TRUNCATE')"
            )
        ).scalar_one()
    )
    if not safe:
        raise AccountEnrollmentPurgeError(
            "account enrollment purge role violates least privilege"
        )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--local-isolated", action="store_true")
    mode.add_argument("--manual-one-shot", action="store_true")
    parser.add_argument("--before", required=True)
    parser.add_argument("--limit", type=int, default=100)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--candidate-digest")
    parser.add_argument("--confirm")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        before = _parse_before(args.before)
        database_url = _validated_database_url(
            os.environ.get(
                "WALKSAFE_ACCOUNT_ENROLLMENT_PURGE_DATABASE_URL",
                "",
            ),
            deployment=args.manual_one_shot,
        )
        if args.apply and (
            args.confirm != CONFIRMATION
            or not isinstance(args.candidate_digest, str)
            or re.fullmatch(r"[0-9a-f]{64}", args.candidate_digest) is None
        ):
            raise AccountEnrollmentPurgeError(
                "apply requires exact confirmation and preview candidate digest"
            )
        engine = create_engine(database_url, pool_pre_ping=True, hide_parameters=True)
        try:
            with Session(engine) as db:
                if args.manual_one_shot:
                    _assert_manual_role(db)
                database_now = db.execute(
                    text("SELECT clock_timestamp()")
                ).scalar_one()
                if before > database_now:
                    raise AccountEnrollmentPurgeError(
                        "purge cutoff must not be in the future"
                    )
                candidates = _candidate_ids(
                    db,
                    before=before,
                    limit=args.limit,
                    lock=args.apply,
                )
                digest = _candidate_digest(candidates)
                if args.apply:
                    if not secrets_compare_digest(args.candidate_digest, digest):
                        raise AccountEnrollmentPurgeError(
                            "candidate inventory changed after preview"
                        )
                    if candidates:
                        deleted_ids = list(
                            db.scalars(
                            delete(AccountEnrollment).where(
                                    AccountEnrollment.id.in_(candidates),
                                    or_(
                                        AccountEnrollment.expires_at <= before,
                                        and_(
                                            AccountEnrollment.state.in_(
                                                TERMINAL_STATES
                                            ),
                                            AccountEnrollment.updated_at <= before,
                                        ),
                                    ),
                                ).returning(AccountEnrollment.id)
                            ).all()
                        )
                        if set(deleted_ids) != set(candidates):
                            raise AccountEnrollmentPurgeError(
                                "candidate inventory changed during deletion"
                            )
                    db.commit()
                else:
                    db.rollback()
        finally:
            engine.dispose()
        print(
            json.dumps(
                {
                    "applied": bool(args.apply),
                    "candidate_count": len(candidates),
                    "candidate_digest": digest,
                    "schema_version": "walksafe.account-enrollment-purge-result.v1",
                },
                sort_keys=True,
                separators=(",", ":"),
            )
        )
        return 0
    except (AccountEnrollmentPurgeError, OSError, SQLAlchemyError) as exc:
        del exc
        print(
            json.dumps(
                {
                    "applied": False,
                    "candidate_count": 0,
                    "status": "failed",
                },
                sort_keys=True,
                separators=(",", ":"),
            ),
            file=sys.stderr,
        )
        return 1


def secrets_compare_digest(left: str, right: str) -> bool:
    return hmac.compare_digest(left, right)


if __name__ == "__main__":
    raise SystemExit(main())
