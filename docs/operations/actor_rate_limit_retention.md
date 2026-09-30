# Shared actor rate-limit event retention

## Scope and deployment boundary

The request path only removes expired rows for the current actor and group.
The checked repository report-retention job concerns report rows, encrypted
uploads, and backup/restore receipts; it does not wire this rate-event job.
This repository observation does not establish which jobs run on a deployed host.

The new module performs one bounded global batch across known rate groups.
It does not modify the request limiter, account creation, schema, database roles,
the report-retention job, or backup workers. Shipping these files does not install,
enable, start, or prove execution of a systemd timer.

## Safety policy

- The database clock supplies the cutoff. There is no user-supplied cutoff.
- Field-group windows come from the current field limiter constants.
- Account configured windows come from the same backend Settings fields used by
  the five account limiters. A window outside 1..86400 seconds stops the job.
- Account rows are retained for at least 86400 seconds, the current Settings
  validation ceiling, even when the local configured window is shorter. This
  prevents a shorter-window maintenance process from removing records still
  relevant to another replica using a permitted longer account window.
- Changing that maximum, adding a group, or using mixed field-window versions
  requires reconciling maintenance policy with all active writers. The program
  refuses unsupported account windows rather than silently deleting earlier.
- Unknown groups are not deleted; preview reports their row count separately.
- Default mode is preview. Only explicit --apply issues a DELETE.
- Option abbreviations are disabled. Argument errors use a fixed JSON response
  without echoing supplied values, and never open a database transaction.
- There is one batch per invocation, default 500 rows, maximum 1000. There is no
  automatic drain loop or unbounded DELETE/TRUNCATE.
- Candidate ordering is observed_at then id. The final DELETE checks the same
  group and cutoff again. Concurrent deletion may reduce the affected count.
- The code uses ordinary DELETE row locking, not FOR UPDATE/SKIP LOCKED, and
  does not add an UPDATE privilege requirement or claim nonblocking execution.
- The existing database statement timeout is applied locally per statement, not
  as a transaction-wide deadline. Preview runs one exact metrics query; apply
  runs one bounded deletion without full-table counts. Success is returned only after
  commit. A commit failure has unknown deletion outcome, not proof that zero rows
  changed. If commit returned successfully and only output failed, stderr reports
  committed with the known deleted count. Missing both reliable receipts leaves
  the observer unable to establish the deletion outcome.

The existing observed_at index remains unchanged. Its presence is not proof of
a particular query plan. Preview's exact aggregate counts can scan many rows and
are bounded by the configured statement timeout, not by the delete batch limit.

## Commands and supplied timer

Run from the deployed backend root with the same validated runtime environment
and virtual environment as the API:

~~~sh
.venv/bin/python -B -m backend.app.services.actor_rate_limit_retention
.venv/bin/python -B -m backend.app.services.actor_rate_limit_retention --apply --batch-size 500
~~~

The first command is read-only preview; the second is an explicit one-batch
database deletion. Neither command has been executed against a database as part
of the local implementation evidence.

The supplied service reads /etc/walksafe/backend-runtime.env as walksafe-backend.
It does not start the API or migrations and has no filesystem write allowlist.
The timer runs after boot and five minutes after the previous invocation becomes
inactive, with jitter. Installing and enabling it is a separate deployment action.
Do not run both a separately deployed equivalent job and this timer unknowingly.
The schedule is an initial bounded operating choice, not a measured capacity
claim; an eligible backlog can grow if its input rate exceeds cleanup throughput.

## Observations

Each successful invocation emits one JSON result to stdout (journal in systemd):

- All modes include schema_version, status, applied, as_of, batch_size, windows,
  deleted_count, and batch_limit_reached.
- Preview includes before with exact total_rows, expired_rows, eligible_rows,
  unknown_group_rows, and table_bytes; after is null and unknown_groups_present
  reflects before. Preview's deleted_count is 0 and batch_limit_reached is false.
- Apply omits before, after, and unknown_groups_present. It does not count the
  table before or after deletion; use a separate preview for exact observations.
- before.total_rows: all table rows at preview time.
- expired_rows: rows beyond this process's configured writer window.
- eligible_rows: known rows beyond the conservative deletion window.
- unknown_group_rows: rows with no known maintenance policy.
- table_bytes: pg_total_relation_size, including indexes and associated storage.
- deleted_count: count returned by this invocation's DELETE after successful
  commit; never an inferred difference between two aggregate observations.
- batch_limit_reached: this batch deleted its allowed row count, not proof that
  all remaining work fits in one more batch.
- windows and as_of: non-secret policy inputs and database-clock cutoff.

Preview is a separate query observation; concurrent traffic can change totals
between preview and apply. Table storage does not necessarily shrink after DELETE. This job
does not run VACUUM or claim to reclaim filesystem space.

Monitor successful apply receipts and failures/timeouts; run preview separately
to track total_rows, eligible_rows, unknown_group_rows, and table_bytes over time. Nonzero unknown groups need a
policy update, not an automatic broad deletion. Repeated full batches with a
growing eligible backlog need a reviewed capacity/schedule adjustment.

## Evidence boundary

Local tests execute the actual policy, SQL construction, transaction orchestration,
CLI, and repository wiring contract with an observing session double. The double
does not calculate expiration or reproduce deletion policy. This does not prove
PostgreSQL SQL execution, row-lock behavior, deployed role permissions, timer
activation, or actual row counts/table sizes.

Operational closure still needs evidence from an authorized disposable PostgreSQL
fixture and the actual deployment: preserve unexpired rows in all twelve groups,
prune inactive actors in bounded batches, retain unknown groups, exercise concurrent
limiter/cleanup transactions and failure rollback, confirm the deployed timer and
credential boundary, and collect the aggregate observations. No operational
database, migration, SMTP, external provider, or systemd activation is performed
by the local regression run.

SQL references:
- https://www.postgresql.org/docs/current/sql-delete.html
- https://www.postgresql.org/docs/current/sql-select.html
- https://www.postgresql.org/docs/current/functions-admin.html
