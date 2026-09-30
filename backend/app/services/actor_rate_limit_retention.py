"""Bounded global cleanup and aggregate observations for shared rate-limit events."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import UTC, datetime
import json
import sys
from typing import Any

from sqlalchemy import text


MAX_BATCH_SIZE = 1_000
DEFAULT_BATCH_SIZE = 500
# Settings currently rejects all account rate windows above this ceiling.
# Keep this ceiling even when this process uses a shorter configured window.
MAX_ACCOUNT_WINDOW_SECONDS = 86_400
_ACCOUNT_GROUPS = (
    "account_enrollment_global",
    "account_enrollment_ip",
    "account_enrollment_email",
    "account_authentication_global",
    "account_authentication_email",
)
_METRICS = (
    "total_rows", "expired_rows", "eligible_rows", "unknown_group_rows", "table_bytes",
)


@dataclass(frozen=True)
class RateWindow:
    configured_seconds: int
    retention_seconds: int


def _seconds(value: Any) -> int:
    if type(value) is not int or not 1 <= value <= MAX_ACCOUNT_WINDOW_SECONDS:
        raise ValueError("Unsupported rate-limit window")
    return value


def retention_policy(settings: Any) -> dict[str, RateWindow]:
    from backend.app.field_test_security import ACTOR_RATE_LIMITS, ACTOR_RATE_WINDOW_SECONDS

    if settings.actor_rate_limit_store != "postgresql":
        raise ValueError("Rate-event maintenance requires the PostgreSQL store")
    field_seconds = int(ACTOR_RATE_WINDOW_SECONDS)
    if field_seconds != ACTOR_RATE_WINDOW_SECONDS:
        raise ValueError("Unsupported field rate-limit window")
    field_seconds = _seconds(field_seconds)
    policy = {
        group: RateWindow(field_seconds, field_seconds)
        for group in ACTOR_RATE_LIMITS
    }
    for group in _ACCOUNT_GROUPS:
        configured = _seconds(getattr(settings, group + "_window_seconds", None))
        if group in policy:
            raise ValueError("Duplicate rate-limit group")
        policy[group] = RateWindow(configured, MAX_ACCOUNT_WINDOW_SECONDS)
    return policy


def _policy_cte(policy: dict[str, RateWindow]) -> tuple[str, dict[str, Any]]:
    rows = []
    parameters: dict[str, Any] = {}
    for index, (group, window) in enumerate(sorted(policy.items())):
        rows.append(
            f"(CAST(:group_{index} AS text), "
            f"CAST(:configured_{index} AS integer), "
            f"CAST(:retention_{index} AS integer))"
        )
        parameters[f"group_{index}"] = group
        parameters[f"configured_{index}"] = window.configured_seconds
        parameters[f"retention_{index}"] = window.retention_seconds
    cte = (
        "WITH policy(rate_group, configured_seconds, retention_seconds) AS (VALUES "
        + ", ".join(rows) + ") "
    )
    return cte, parameters


def _metrics(db: Any, cte: str, parameters: dict[str, Any]) -> dict[str, int]:
    row = db.execute(
        text(
            cte
            + "SELECT count(*) AS total_rows, "
            "count(*) FILTER (WHERE p.rate_group IS NOT NULL AND "
            "e.observed_at <= CAST(:as_of AS timestamp with time zone) - "
            "make_interval(secs => p.configured_seconds)) AS expired_rows, "
            "count(*) FILTER (WHERE p.rate_group IS NOT NULL AND "
            "e.observed_at <= CAST(:as_of AS timestamp with time zone) - "
            "make_interval(secs => p.retention_seconds)) AS eligible_rows, "
            "count(*) FILTER (WHERE p.rate_group IS NULL) AS unknown_group_rows, "
            "pg_total_relation_size('public.actor_rate_limit_events'::regclass) "
            "AS table_bytes "
            "FROM public.actor_rate_limit_events AS e "
            "LEFT JOIN policy AS p ON p.rate_group = e.rate_group"
        ),
        parameters,
    ).mappings().one()
    result = {key: row[key] for key in _METRICS}
    if any(type(value) is not int or value < 0 for value in result.values()):
        raise ValueError("Invalid rate-event metrics")
    if not (
        result["eligible_rows"] <= result["expired_rows"]
        and result["expired_rows"] + result["unknown_group_rows"] <= result["total_rows"]
    ):
        raise ValueError("Inconsistent rate-event metrics")
    return result


def run_retention(
    settings: Any,
    *,
    session_factory: Any = None,
    apply: bool = False,
    batch_size: int = DEFAULT_BATCH_SIZE,
) -> dict[str, Any]:
    """Run one transaction and at most one delete, never a drain-until-empty loop."""
    if type(apply) is not bool:
        raise ValueError("Invalid maintenance mode")
    if type(batch_size) is not int or not 1 <= batch_size <= MAX_BATCH_SIZE:
        raise ValueError("Batch size must be between 1 and 1000")
    timeout_ms = settings.database_statement_timeout_ms
    if type(timeout_ms) is not int or not 1 <= timeout_ms <= 120_000:
        raise ValueError("Invalid database statement timeout")
    policy = retention_policy(settings)
    cte, parameters = _policy_cte(policy)
    if session_factory is None:
        from backend.app.database import SessionLocal

        session_factory = SessionLocal

    deleted_count = 0
    with session_factory.begin() as db:
        if not apply:
            db.execute(text("SET TRANSACTION READ ONLY"))
        db.execute(
            text("SELECT set_config('statement_timeout', :timeout_ms, true)"),
            {"timeout_ms": str(timeout_ms)},
        )
        as_of = db.execute(text("SELECT clock_timestamp()")).scalar_one()
        if (
            not isinstance(as_of, datetime)
            or as_of.tzinfo is None
            or as_of.utcoffset() is None
        ):
            raise ValueError("Invalid database clock")
        as_of = as_of.astimezone(UTC)
        parameters["as_of"] = as_of
        if apply:
            delete_parameters = {
                **parameters,
                "batch_size": batch_size,
                "minimum_retention_seconds": min(
                    window.retention_seconds for window in policy.values()
                ),
            }
            deleted_count = db.execute(
                text(
                    cte
                    + ", candidates AS ("
                    "SELECT e.id FROM public.actor_rate_limit_events AS e "
                    "JOIN policy AS p ON p.rate_group = e.rate_group "
                    "WHERE e.observed_at <= CAST(:as_of AS timestamp with time zone) - "
                    "make_interval(secs => :minimum_retention_seconds) "
                    "AND e.observed_at <= CAST(:as_of AS timestamp with time zone) - "
                    "make_interval(secs => p.retention_seconds) "
                    "ORDER BY e.observed_at, e.id LIMIT :batch_size"
                    "), deleted AS ("
                    "DELETE FROM public.actor_rate_limit_events AS e "
                    "USING candidates AS c, policy AS p "
                    "WHERE e.id = c.id AND e.rate_group = p.rate_group "
                    "AND e.observed_at <= CAST(:as_of AS timestamp with time zone) - "
                    "make_interval(secs => p.retention_seconds) "
                    "RETURNING 1"
                    ") SELECT count(*) FROM deleted"
                ),
                delete_parameters,
            ).scalar_one()
            if type(deleted_count) is not int or not 0 <= deleted_count <= batch_size:
                raise ValueError("Invalid bounded delete result")
        else:
            before = _metrics(db, cte, parameters)

    # Only return success after the transaction context has committed.
    result = {
        "schema_version": "walksafe.actor-rate-retention.v1",
        "status": "completed" if apply else "preview",
        "applied": apply,
        "as_of": as_of.isoformat(),
        "batch_size": batch_size,
        "deleted_count": deleted_count,
        "batch_limit_reached": apply and deleted_count == batch_size,
        "windows": {
            group: {
                "configured_seconds": window.configured_seconds,
                "retention_seconds": window.retention_seconds,
            }
            for group, window in sorted(policy.items())
        },
    }
    if not apply:
        result["before"] = before
        result["after"] = None
        result["unknown_groups_present"] = bool(before["unknown_group_rows"])
    return result


class _SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        del message
        self.exit(
            2,
            json.dumps({
                "schema_version": "walksafe.actor-rate-retention.v1",
                "status": "invalid_arguments",
                "deletion_outcome": "not_attempted",
            }, sort_keys=True, separators=(",", ":")) + "\n",
        )


def main(
    argv: list[str] | None = None,
    *,
    settings: Any = None,
    session_factory: Any = None,
) -> int:
    parser = _SafeArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--apply", action="store_true", help="Delete one bounded expired batch")
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    args = parser.parse_args(argv)
    result = None
    try:
        if settings is None:
            from backend.app.config import get_settings

            settings = get_settings()
        result = run_retention(
            settings, session_factory=session_factory,
            apply=args.apply, batch_size=args.batch_size,
        )
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        return 0
    except Exception:
        # run_retention only returns after commit. Preserve that known outcome
        # when output fails; a commit exception itself still has unknown outcome.
        failure = {
            "schema_version": "walksafe.actor-rate-retention.v1",
            "status": "output_failed" if result is not None else "failed",
            "mode": "apply" if args.apply else "preview",
            "deletion_outcome": "unknown" if args.apply else "not_attempted",
        }
        if args.apply and result is not None:
            failure["deletion_outcome"] = "committed"
            failure["deleted_count"] = result["deleted_count"]
        print(
            json.dumps(failure, sort_keys=True, separators=(",", ":")),
            file=sys.stderr,
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
