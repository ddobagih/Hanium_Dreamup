# PostgreSQL rate-admission execution bounds

The PostgreSQL event-window algorithm and actor/group advisory keys are unchanged.
All default PostgresActorRateLimiter instances in one backend process share one
bounded execution pool, including field traffic and the five account buckets.

## Configuration

- WALKSAFE_ACTOR_RATE_LIMIT_MAX_IN_FLIGHT: default 4; integer 1 through 64.
- WALKSAFE_ACTOR_RATE_LIMIT_BUDGET_MS: default 5000; integer 1 through 120000.
- DATABASE_STATEMENT_TIMEOUT_MS remains a ceiling. Each statement receives the
  smaller of that ceiling and the remaining decision budget, transaction-locally.
- These defaults are conservative policy choices, not measured optimal capacity.
  The fleet bound scales with worker and replica counts; this is not a
  cluster-wide concurrency semaphore.

The monotonic decision budget starts at check entry, before default dispatcher
acquisition, and covers the worker's hashing, connection attempt, SQL, and
transaction completion. Default dispatcher lock contention is rejected without
waiting; time spent acquiring or initializing it does not restart the budget. Both worker
and caller check the same deadline before returning an admission or quota result.
The field middleware submits before the general asyncio thread queue. Synchronous
account callers use the same execution pool. Multi-bucket account checks remain
sequential, with one budget per bucket check; this is not a total HTTP-service SLO.

## Timeout and capacity semantics

Admission to the pool is non-waiting. Full capacity returns the existing store
unavailable response (503), not a fabricated quota response (429). Existing
429 Retry-After behavior is retained after a timely database decision.

A timeout or caller cancellation does not kill a running Python thread or force
PostgreSQL to stop. A worker retains its capacity until its database action and
Session cleanup terminate. Pending work can be cancelled before it starts.
Completion and cancellation share release-once accounting. Further requests
cannot accumulate an unbounded queue while timed-out database calls remain alive.

Checkpoints prevent subsequent work after an observed expired/cancelled deadline.
A commit already in progress can nevertheless complete after caller timeout and
consume quota; no late success is returned to that caller. Connection, rollback,
driver/network, OS scheduling, and executor startup are not hard real-time
cancellation guarantees. Existing database connection/statement bounds still
apply while cleanup is pending.

If executor submission raises, enqueueing may already have happened. The
dispatcher is quarantined and refuses new submissions rather than releasing
uncertain capacity for reuse. Any action that did start still performs its own
cleanup and release-once accounting. A closed dispatcher rejects before acquiring
capacity. A quarantined process requires normal service recovery; this change
does not restart or reconfigure the deployed service automatically.

## Evidence boundary

Local regressions use real dispatchers, threads, futures, middleware, account
facades, and the production SQL construction, with synthetic DB results and
controlled timing/failure boundaries. They do not establish real PostgreSQL
execution plans, replica load capacity, driver cancellation, deployment behavior,
or an operational latency SLO. No event table, ACL, index, migration, or aggregate
rate algorithm is changed. The existing PostgreSQL integration tests are retained
but are not run as part of the guarded local selection.

Primary behavior references:
- https://docs.python.org/3/library/concurrent.futures.html
- https://docs.python.org/3/library/asyncio-task.html
- https://docs.sqlalchemy.org/en/20/orm/session_transaction.html
- https://www.postgresql.org/docs/current/runtime-config-client.html
