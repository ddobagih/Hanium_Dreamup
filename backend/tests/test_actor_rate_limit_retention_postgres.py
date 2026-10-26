"""S20-45: bounded rate-event cleanup against real PostgreSQL SQL, locks and role.

The fake-SQL tests in test_actor_rate_limit_retention.py pin statement shape.
This module runs the same service against the migrated table as the Backend
runtime role: one bounded, oldest-first batch per call, writer windows and the
account 24 h ceiling preserved, rows of groups unknown to this code version
preserved, and aggregate observations equal to the table's real values.
"""
from __future__ import annotations

import os
import threading
from concurrent.futures import ThreadPoolExecutor
import json
import time

import pytest
from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import sessionmaker
from types import SimpleNamespace

from backend.app import field_test_security
from backend.app.services.actor_rate_limit_retention import main, run_retention

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_ACCOUNT_WINDOW_SECONDS = 900
# (label, rate_group, age in seconds). admin_read is made unknown below.
_ROWS = (
    ("eligible_account", "account_authentication_email", 172_800),
    ("eligible_report_1", "report", 600),
    ("eligible_report_2", "report", 500),
    ("eligible_navigation", "navigation", 400),
    ("eligible_detect", "detect", 300),
    ("fresh_report", "report", 5),
    ("account_within_ceiling", "account_authentication_email", 3_600),
    ("unknown_group", "admin_read", 600),
    ("fresh_account", "account_enrollment_ip", 10),
)
_ELIGIBLE_OLDEST_FIRST = (
    "eligible_account", "eligible_report_1", "eligible_report_2",
    "eligible_navigation", "eligible_detect",
)
_PRESERVED = ("fresh_report", "account_within_ceiling", "unknown_group", "fresh_account")


def _settings() -> SimpleNamespace:
    values = {
        "actor_rate_limit_store": "postgresql",
        "database_statement_timeout_ms": 5_000,
    }
    for group in (
        "account_enrollment_global", "account_enrollment_ip", "account_enrollment_email",
        "account_authentication_global", "account_authentication_email",
    ):
        values[group + "_window_seconds"] = _ACCOUNT_WINDOW_SECONDS
    return SimpleNamespace(**values)


@pytest.fixture
def database(monkeypatch: pytest.MonkeyPatch):
    url = os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()
    owner = create_engine(url, pool_pre_ping=True)
    runtime = create_engine(url, pool_pre_ping=True)

    @event.listens_for(runtime, "connect")
    def _runtime_role(dbapi_connection, _record) -> None:
        with dbapi_connection.cursor() as cursor:
            cursor.execute("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        dbapi_connection.commit()

    # A newer writer may emit a group this maintenance build does not know.
    known = dict(field_test_security.ACTOR_RATE_LIMITS)
    known.pop("admin_read")
    monkeypatch.setattr(field_test_security, "ACTOR_RATE_LIMITS", known)
    with owner.begin() as connection:
        connection.execute(text("DELETE FROM public.actor_rate_limit_events"))
        for label, group, age in _ROWS:
            connection.execute(
                text(
                    "INSERT INTO public.actor_rate_limit_events "
                    "(actor_digest, rate_group, observed_at) VALUES "
                    "(:digest, :group, clock_timestamp() - make_interval(secs => :age))"
                ),
                {"digest": label, "group": group, "age": age},
            )
    try:
        yield owner, sessionmaker(bind=runtime, expire_on_commit=False)
    finally:
        with owner.begin() as connection:
            connection.execute(text("DELETE FROM public.actor_rate_limit_events"))
        runtime.dispose()
        owner.dispose()


def _remaining(owner) -> set[str]:
    with owner.connect() as connection:
        return set(connection.execute(
            text("SELECT actor_digest FROM public.actor_rate_limit_events")
        ).scalars())


def test_s20_45_preview_reports_real_aggregates_without_deleting(database) -> None:
    owner, runtime_sessions = database
    with owner.connect() as connection:
        table_bytes = connection.execute(text(
            "SELECT pg_total_relation_size('public.actor_rate_limit_events'::regclass)"
        )).scalar_one()
    result = run_retention(_settings(), session_factory=runtime_sessions)
    assert result["status"] == "preview"
    assert result["deleted_count"] == 0
    assert result["before"] == {
        "total_rows": len(_ROWS),
        # Five eligible rows plus the account row older than its 900 s window
        # but inside the 24 h ceiling. The unknown group is counted separately.
        "expired_rows": len(_ELIGIBLE_OLDEST_FIRST) + 1,
        "eligible_rows": len(_ELIGIBLE_OLDEST_FIRST),
        "unknown_group_rows": 1,
        "table_bytes": table_bytes,
    }
    assert table_bytes > 0
    assert result["unknown_groups_present"] is True
    assert "admin_read" not in result["windows"]
    assert result["windows"]["account_authentication_email"] == {
        "configured_seconds": _ACCOUNT_WINDOW_SECONDS, "retention_seconds": 86_400,
    }
    assert _remaining(owner) == {label for label, _group, _age in _ROWS}


def test_s20_45_apply_deletes_one_bounded_oldest_batch_as_runtime_role(database) -> None:
    owner, runtime_sessions = database
    first = run_retention(_settings(), session_factory=runtime_sessions, apply=True, batch_size=3)
    assert (first["deleted_count"], first["batch_limit_reached"]) == (3, True)
    assert _remaining(owner) == set(_ELIGIBLE_OLDEST_FIRST[3:]) | set(_PRESERVED)

    second = run_retention(_settings(), session_factory=runtime_sessions, apply=True, batch_size=3)
    assert (second["deleted_count"], second["batch_limit_reached"]) == (2, False)
    third = run_retention(_settings(), session_factory=runtime_sessions, apply=True, batch_size=3)
    assert (third["deleted_count"], third["batch_limit_reached"]) == (0, False)
    # Writer windows, the account ceiling and unknown groups are never drained.
    assert _remaining(owner) == set(_PRESERVED)

    after = run_retention(_settings(), session_factory=runtime_sessions)
    assert after["before"]["total_rows"] == len(_PRESERVED)
    assert after["before"]["eligible_rows"] == 0
    assert after["before"]["unknown_group_rows"] == 1


def test_s20_45_cli_apply_does_not_wait_for_a_concurrent_writer(database, capsys) -> None:
    owner, runtime_sessions = database
    writer = runtime_sessions()
    try:
        # An uncommitted runtime insert (a live request window) holds only its
        # own row; the bounded delete neither waits for nor removes it.
        writer.execute(text(
            "INSERT INTO public.actor_rate_limit_events (actor_digest, rate_group) "
            "VALUES ('concurrent_writer', 'navigation')"
        ))
        outcome: dict[str, int] = {}
        worker = threading.Thread(target=lambda: outcome.setdefault(
            "rc", main(["--apply", "--batch-size", "10"], settings=_settings(),
                       session_factory=runtime_sessions),
        ))
        worker.start()
        worker.join(timeout=10)
        assert not worker.is_alive()
        assert outcome == {"rc": 0}
        writer.commit()
    finally:
        writer.close()
    printed = capsys.readouterr().out
    assert '"deleted_count":5' in printed and '"status":"completed"' in printed
    assert _remaining(owner) == set(_PRESERVED) | {"concurrent_writer"}


def test_locked_expired_row_times_out_without_partial_deletion_and_retry_recovers(
    database, capsys,
) -> None:
    owner, runtime_sessions = database
    settings = _settings()
    settings.database_statement_timeout_ms = 100
    before = _remaining(owner)
    with owner.connect() as blocker:
        transaction = blocker.begin()
        try:
            blocker.execute(text(
                "SELECT id FROM public.actor_rate_limit_events "
                "WHERE actor_digest = 'eligible_account' FOR UPDATE"
            )).scalar_one()
            started = time.monotonic()
            assert main(
                ["--apply", "--batch-size", "3"], settings=settings,
                session_factory=runtime_sessions,
            ) == 1
            assert time.monotonic() - started < 3
            captured = capsys.readouterr()
            assert captured.out == ""
            assert json.loads(captured.err)["deletion_outcome"] == "unknown"
            assert _remaining(owner) == before
        finally:
            transaction.rollback()
    retried = run_retention(
        settings, session_factory=runtime_sessions, apply=True, batch_size=3,
    )
    assert retried["deleted_count"] == 3
    assert _remaining(owner) == set(_ELIGIBLE_OLDEST_FIRST[3:]) | set(_PRESERVED)


def test_concurrent_cleaners_preserve_live_and_unknown_groups(database) -> None:
    owner, runtime_sessions = database
    start = threading.Barrier(2)

    def clean():
        start.wait(timeout=5)
        return run_retention(
            _settings(), session_factory=runtime_sessions, apply=True, batch_size=3,
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        calls = [pool.submit(clean) for _ in range(2)]
        results = [call.result(timeout=10) for call in calls]
    remaining = _remaining(owner)
    assert set(_PRESERVED) <= remaining
    assert all(0 <= result["deleted_count"] <= 3 for result in results)
    assert sum(result["deleted_count"] for result in results) == len(_ROWS) - len(remaining)
    # Overlapping batches may select the same rows. A later explicit batch
    # must still drain the eligible remainder without touching live windows.
    final = run_retention(
        _settings(), session_factory=runtime_sessions, apply=True, batch_size=10,
    )
    assert final["deleted_count"] == len(remaining) - len(_PRESERVED)
    assert _remaining(owner) == set(_PRESERVED)
