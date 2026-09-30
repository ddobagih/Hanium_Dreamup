from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, datetime
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from backend.app.api.health import EXPECTED_ACTOR_RATE_LIMIT_GROUPS
from backend.app.field_test_security import ACTOR_RATE_LIMITS, ACTOR_RATE_WINDOW_SECONDS
from backend.app.services import actor_rate_limit_retention as retention


ACCOUNT_WINDOWS = {
    "account_enrollment_global": 60,
    "account_enrollment_ip": 600,
    "account_enrollment_email": 3600,
    "account_authentication_global": 60,
    "account_authentication_email": 900,
}
DATABASE_NOW = datetime(2026, 9, 21, 12, 0, tzinfo=UTC)
BEFORE = dict(total_rows=1200, expired_rows=1100, eligible_rows=1001,
              unknown_group_rows=2, table_bytes=131072)


def settings():
    return SimpleNamespace(
        actor_rate_limit_store="postgresql",
        database_statement_timeout_ms=10000,
        **{group + "_window_seconds": seconds for group, seconds in ACCOUNT_WINDOWS.items()},
    )


class Result:
    def __init__(self, value=None):
        self.value = value

    def scalar_one(self):
        return self.value

    def mappings(self):
        return self

    def one(self):
        return dict(self.value)


class ObservedSessions:
    """Observe transactions and SQL only; do not emulate expiration or deletion."""

    def __init__(self, *, deleted_count=500, fail_at=None, database_now=DATABASE_NOW):
        self.deleted_count = deleted_count
        self.fail_at = fail_at
        self.database_now = database_now
        self.calls = []
        self.metrics_calls = 0
        self.begins = 0
        self.commit_attempts = 0
        self.commits = 0
        self.rollbacks = 0

    @contextmanager
    def begin(self):
        self.begins += 1
        try:
            yield self
            self.commit_attempts += 1
            self.fail("commit")
            self.commits += 1
        except BaseException:
            self.rollbacks += 1
            raise

    def fail(self, stage):
        if stage == self.fail_at:
            raise RuntimeError("private_actor private_password postgresql://private_url")

    def execute(self, statement, parameters=None):
        sql = str(statement)
        self.calls.append((sql, dict(parameters or {})))
        if sql == "SET TRANSACTION READ ONLY" or "set_config(" in sql:
            return Result()
        if sql == "SELECT clock_timestamp()":
            return Result(self.database_now)
        if "DELETE FROM public.actor_rate_limit_events" in sql:
            self.fail("delete")
            return Result(self.deleted_count)
        assert "pg_total_relation_size(" in sql
        self.metrics_calls += 1
        self.fail("metrics")
        return Result(BEFORE)


def test_policy_reads_writer_windows_and_preserves_the_account_ceiling():
    policy = retention.retention_policy(settings())
    assert set(policy) == set(ACTOR_RATE_LIMITS) | set(ACCOUNT_WINDOWS)
    for group in ACTOR_RATE_LIMITS:
        assert policy[group].configured_seconds == ACTOR_RATE_WINDOW_SECONDS
        assert policy[group].retention_seconds == ACTOR_RATE_WINDOW_SECONDS
    for group, seconds in ACCOUNT_WINDOWS.items():
        assert policy[group].configured_seconds == seconds
        assert policy[group].retention_seconds == 86400
    changed = settings()
    changed.account_enrollment_email_window_seconds = 86400
    assert retention.retention_policy(changed)["account_enrollment_email"] == retention.RateWindow(
        configured_seconds=86400, retention_seconds=86400,
    )


@pytest.mark.parametrize("group", tuple(ACCOUNT_WINDOWS))
@pytest.mark.parametrize("invalid", (0, -1, 86401, True, 60.5, "60"))
def test_invalid_account_windows_fail_before_a_transaction(group, invalid):
    configured = settings()
    setattr(configured, group + "_window_seconds", invalid)
    db = ObservedSessions()
    with pytest.raises(ValueError):
        retention.run_retention(configured, session_factory=db, apply=True)
    assert db.begins == 0 and db.calls == []


def test_preview_is_read_only_and_reports_expired_and_eligible_rows_separately():
    db = ObservedSessions()
    result = retention.run_retention(settings(), session_factory=db)

    assert result["status"] == "preview"
    assert result["applied"] is False
    assert result["deleted_count"] == 0
    assert result["before"] == BEFORE
    assert result["after"] is None
    assert result["unknown_groups_present"] is True
    assert result["as_of"] == DATABASE_NOW.isoformat()
    assert result["batch_limit_reached"] is False
    assert db.calls[0][0] == "SET TRANSACTION READ ONLY"
    assert not any("DELETE " in sql or "FOR UPDATE" in sql for sql, _ in db.calls)
    assert db.begins == db.commits == 1
    assert db.rollbacks == 0
    assert db.metrics_calls == 1
    metric_sql, bound = db.calls[-1]
    assert "LEFT JOIN policy" in metric_sql
    assert "p.rate_group IS NULL" in metric_sql
    assert "p.configured_seconds" in metric_sql and "p.retention_seconds" in metric_sql
    assert "public.actor_rate_limit_events" in metric_sql
    assert bound["as_of"] is DATABASE_NOW


def test_apply_runs_one_bounded_delete_and_rechecks_the_same_group_and_cutoff():
    db = ObservedSessions()
    result = retention.run_retention(settings(), session_factory=db, apply=True)

    assert result["status"] == "completed" and result["applied"] is True
    assert not {"before", "after", "unknown_groups_present"} & result.keys()
    assert result["deleted_count"] == 500 and result["batch_limit_reached"] is True
    assert db.begins == db.commit_attempts == db.commits == 1
    assert db.rollbacks == 0
    assert db.metrics_calls == 0
    deletes = [(sql, parameters) for sql, parameters in db.calls if "DELETE FROM" in sql]
    assert len(deletes) == 1
    sql, parameters = deletes[0]
    assert "ORDER BY e.observed_at, e.id LIMIT :batch_size" in sql
    assert "JOIN policy AS p ON p.rate_group = e.rate_group" in sql
    assert "USING candidates AS c, policy AS p" in sql
    assert "WHERE e.id = c.id AND e.rate_group = p.rate_group" in sql
    assert sql.count("make_interval(secs => p.retention_seconds)") == 2
    assert "RETURNING 1" in sql
    assert "SELECT count(*) FROM deleted" in sql
    assert "TRUNCATE" not in sql and "FOR UPDATE" not in sql and "SKIP LOCKED" not in sql
    assert "actor_digest" not in sql
    assert parameters["batch_size"] == 500
    assert parameters["minimum_retention_seconds"] == 60
    assert parameters["as_of"] is DATABASE_NOW
    for index, group in enumerate(sorted(set(ACTOR_RATE_LIMITS) | set(ACCOUNT_WINDOWS))):
        assert parameters[f"group_{index}"] == group
        assert parameters[f"retention_{index}"] == (86400 if group in ACCOUNT_WINDOWS else 60)
        assert group not in sql  # Group names are bound, never interpolated into SQL.
    assert db.calls[0] == (
        "SELECT set_config('statement_timeout', :timeout_ms, true)", {"timeout_ms": "10000"},
    )
    assert [query for query, _ in db.calls if "count(*)" in query] == [sql]
    rendered = json.dumps(result)
    assert "actor_digest" not in rendered and "private_" not in rendered


def test_cli_apply_never_runs_unbounded_count_even_if_metrics_would_fail(capsys):
    db = ObservedSessions(deleted_count=7, fail_at="metrics")
    assert retention.main(
        ["--apply", "--batch-size", "7"], settings=settings(), session_factory=db,
    ) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["deleted_count"] == 7
    assert result["batch_limit_reached"] is True
    assert not {"before", "after", "unknown_groups_present"} & result.keys()
    assert db.metrics_calls == 0
    assert len([sql for sql, _ in db.calls if "DELETE FROM" in sql]) == 1
    assert all(
        "count(*)" not in sql or "SELECT count(*) FROM deleted" in sql
        for sql, _ in db.calls
    )


@pytest.mark.parametrize("invalid", (0, 1001, -1, True, 1.5, "500"))
def test_invalid_batch_size_never_opens_a_transaction(invalid):
    db = ObservedSessions()
    with pytest.raises(ValueError):
        retention.run_retention(settings(), session_factory=db, apply=True, batch_size=invalid)
    assert db.begins == 0 and db.calls == []


def test_maximum_batch_can_report_zero_deleted_without_claiming_the_table_is_empty():
    db = ObservedSessions(deleted_count=0)
    result = retention.run_retention(
        settings(), session_factory=db, apply=True, batch_size=1000,
    )
    assert result["deleted_count"] == 0
    assert result["batch_limit_reached"] is False
    assert not {"before", "after", "unknown_groups_present"} & result.keys()
    assert db.metrics_calls == 0
    assert len([sql for sql, _ in db.calls if "DELETE FROM" in sql]) == 1
    assert db.commits == 1


def test_preview_metric_failure_rolls_back_without_delete():
    db = ObservedSessions(fail_at="metrics")
    with pytest.raises(RuntimeError):
        retention.run_retention(settings(), session_factory=db)
    assert db.metrics_calls == 1
    assert db.rollbacks == 1 and db.commits == 0
    assert not any("DELETE FROM" in sql for sql, _ in db.calls)


@pytest.mark.parametrize("stage", ("delete", "commit"))
def test_failure_rolls_back_without_a_second_batch_or_success_result(stage):
    db = ObservedSessions(fail_at=stage)
    with pytest.raises(RuntimeError):
        retention.run_retention(settings(), session_factory=db, apply=True)
    assert db.rollbacks == 1
    assert db.commits == 0
    assert len([sql for sql, _ in db.calls if "DELETE FROM" in sql]) <= 1
    assert db.begins == 1


@pytest.mark.parametrize("clock", (datetime(2026, 9, 21, 12, 0), "2026-09-21"))
def test_invalid_database_clock_cannot_reach_metrics_or_delete(clock):
    db = ObservedSessions(database_now=clock)
    with pytest.raises(ValueError):
        retention.run_retention(settings(), session_factory=db, apply=True)
    assert db.rollbacks == 1 and db.commits == 0
    assert db.metrics_calls == 0
    assert not any("DELETE FROM" in sql for sql, _ in db.calls)


@pytest.mark.parametrize("apply", (False, True))
def test_cli_defaults_to_preview_and_only_explicit_apply_deletes(apply, capsys):
    db = ObservedSessions()
    args = ["--apply"] if apply else []
    assert retention.main(args, settings=settings(), session_factory=db) == 0
    captured = capsys.readouterr()
    assert captured.err == ""
    result = json.loads(captured.out)
    assert result["applied"] is apply
    assert result["status"] == ("completed" if apply else "preview")
    assert result["deleted_count"] == (500 if apply else 0)
    assert len([sql for sql, _ in db.calls if "DELETE FROM" in sql]) == int(apply)
    assert db.commits == 1


@pytest.mark.parametrize("apply", (False, True))
def test_cli_failure_does_not_invent_a_commit_outcome_or_leak_private_data(apply, capsys):
    db = ObservedSessions(fail_at="commit")
    args = ["--apply"] if apply else []
    assert retention.main(args, settings=settings(), session_factory=db) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    result = json.loads(captured.err)
    assert result == {
        "schema_version": "walksafe.actor-rate-retention.v1",
        "status": "failed",
        "mode": "apply" if apply else "preview",
        "deletion_outcome": "unknown" if apply else "not_attempted",
    }
    assert "deleted_count" not in result
    assert "private_" not in captured.err and "postgresql://" not in captured.err


@pytest.mark.parametrize(("args", "expected"), (
    (["--batch-size", "0"], 1),
    (["--batch-size", "1001"], 1),
    (["--batch-size", "abc"], 2),
    (["--as-of", "2099-01-01"], 2),
))
def test_invalid_cli_arguments_do_not_open_the_database(args, expected, capsys):
    db = ObservedSessions()
    if expected == 2:
        with pytest.raises(SystemExit) as rejected:
            retention.main(args, settings=settings(), session_factory=db)
        assert rejected.value.code == expected
    else:
        assert retention.main(args, settings=settings(), session_factory=db) == expected
    assert db.begins == 0 and db.calls == []
    assert capsys.readouterr().out == ""


@pytest.mark.parametrize("store", ("memory", "unknown"))
def test_non_postgresql_configuration_is_not_an_implicit_database_cleanup(store):
    configured = settings()
    configured.actor_rate_limit_store = store
    db = ObservedSessions()
    with pytest.raises(ValueError):
        retention.run_retention(configured, session_factory=db, apply=True)
    assert db.begins == 0 and db.calls == []


def test_maintenance_policy_covers_the_current_shared_table_groups():
    assert set(retention.retention_policy(settings())) == EXPECTED_ACTOR_RATE_LIMIT_GROUPS


@pytest.mark.parametrize("apply", (False, True))
def test_committed_result_then_output_failure_preserves_the_known_outcome(
    apply, capsys, monkeypatch,
):
    db = ObservedSessions()
    real_print = print

    def fail_stdout(*args, **kwargs):
        if kwargs.get("file") is None:
            raise OSError("private_output_sink")
        return real_print(*args, **kwargs)

    monkeypatch.setattr(retention, "print", fail_stdout, raising=False)
    args = ["--apply"] if apply else []
    assert retention.main(args, settings=settings(), session_factory=db) == 1
    captured = capsys.readouterr()
    assert captured.out == ""
    failure = json.loads(captured.err)
    assert db.commits == 1 and db.rollbacks == 0
    assert failure["status"] == "output_failed"
    assert failure["deletion_outcome"] == ("committed" if apply else "not_attempted")
    if apply:
        assert failure["deleted_count"] == 500
    else:
        assert "deleted_count" not in failure
    assert "private_output_sink" not in captured.err


@pytest.mark.parametrize("args", (
    ["--batch-size", "private_password"],
    ["--unknown", "postgresql://private_user:private_password@private_host/db"],
    ["--apply", "--unknown", "private_actor"],
    ["--apply=private_password"],
    ["--", "private_actor"],
))
def test_cli_argument_errors_never_echo_user_supplied_values(args, capsys):
    db = ObservedSessions()
    with pytest.raises(SystemExit) as rejected:
        retention.main(args, settings=settings(), session_factory=db)
    assert rejected.value.code == 2
    captured = capsys.readouterr()
    assert captured.out == ""
    assert json.loads(captured.err) == {
        "schema_version": "walksafe.actor-rate-retention.v1",
        "status": "invalid_arguments",
        "deletion_outcome": "not_attempted",
    }
    assert "private_" not in captured.err and "postgresql://" not in captured.err
    assert db.begins == 0 and db.calls == []


@pytest.mark.parametrize("args", (["--ap"], ["--app"], ["--batch", "1"]))
def test_cli_option_abbreviations_cannot_authorize_apply_or_change_batch_size(args, capsys):
    db = ObservedSessions()
    with pytest.raises(SystemExit) as rejected:
        retention.main(args, settings=settings(), session_factory=db)
    assert rejected.value.code == 2
    assert json.loads(capsys.readouterr().err)["deletion_outcome"] == "not_attempted"
    assert db.begins == 0 and db.calls == []


@pytest.mark.parametrize("timeout", (0, -1, 120001, True, 1.5, "10000"))
def test_statement_timeout_rejects_values_outside_existing_config_contract(timeout):
    configured = settings()
    configured.database_statement_timeout_ms = timeout
    db = ObservedSessions()
    with pytest.raises(ValueError):
        retention.run_retention(configured, session_factory=db, apply=True)
    assert db.begins == 0 and db.calls == []


def test_statement_timeout_accepts_existing_config_upper_bound():
    configured = settings()
    configured.database_statement_timeout_ms = 120000
    db = ObservedSessions()
    result = retention.run_retention(configured, session_factory=db, apply=True)
    assert db.calls[0] == (
        "SELECT set_config('statement_timeout', :timeout_ms, true)", {"timeout_ms": "120000"},
    )
    assert result["deleted_count"] == 500 and db.commits == 1


def test_scheduler_wiring_uses_runtime_settings_without_changing_or_starting_the_api():
    root = Path(__file__).resolve().parents[2]
    service = (root / "deploy/systemd/walksafe-actor-rate-retention.service").read_text()
    timer = (root / "deploy/systemd/walksafe-actor-rate-retention.timer").read_text()
    assert "EnvironmentFile=/etc/walksafe/backend-runtime.env" in service
    assert "User=walksafe-backend" in service
    assert (
        "ExecStart=/srv/walksafe/backend/.venv/bin/python -B -m "
        "backend.app.services.actor_rate_limit_retention --apply --batch-size 500"
    ) in service
    assert "Requires=walksafe-backend" not in service
    assert "ProtectSystem=strict" in service
    assert "ReadWritePaths=" not in service
    assert "TimeoutStartSec=10min" in service
    assert "Unit=walksafe-actor-rate-retention.service" in timer
    assert "OnUnitInactiveSec=5m" in timer
    assert "WantedBy=timers.target" in timer
