package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One overall monotonic deadline per HTTP request for the operations and raw-collection
 * HttpURLConnection transports (S18-19). The security client keeps its own copy of the
 * same rule; the report and incident clients use the OkHttp callTimeout with the same budget.
 *
 * <p>The budget is the existing connect + read budgets (8 s + 12 s = 20 s). Opening the
 * connection, writing the body, waiting for headers and reading the body all count. At the
 * deadline a scheduled task disconnects that one connection (off the scheduler thread) and
 * the request fails with a {@link SocketTimeoutException} that says the server outcome is
 * unknown. {@link #cancelInFlight()} is the explicit cancellation for lifecycle owners.
 * Nothing here retries: a timed-out or cancelled write is never re-sent automatically.
 *
 * <p>R14 (adm-F1): only the one-time original image GET gets a size-based budget,
 * {@link #originalEvidenceBudgetNanos(int)} = 20 s + expected bytes / 64 KiB/s, capped at
 * 150 s. JSON reads and every write keep the 20 s budget.
 */
final class AdminHttpRequestDeadline {
    static final int CONNECT_TIMEOUT_MS = 8_000;
    static final int READ_TIMEOUT_MS = 12_000;
    static final long REQUEST_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(
        CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS
    );
    /** R14: the slowest sustained original image throughput the budget still accepts. */
    static final long ORIGINAL_EVIDENCE_FLOOR_BYTES_PER_SECOND = 64L * 1024L;
    /** R14: upper bound of the original image budget, whatever the declared size. */
    static final long ORIGINAL_EVIDENCE_MAX_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(150);
    static final String DEADLINE_MESSAGE =
        "administrator HTTP request deadline exceeded; server outcome unknown";
    static final String CANCELLED_MESSAGE =
        "administrator HTTP request was cancelled; server outcome unknown";

    private static final ScheduledExecutorService DEADLINES =
        Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "walksafe-admin-http-deadline");
            thread.setDaemon(true);
            return thread;
        });

    interface ConnectionFactory {
        HttpURLConnection open(String url) throws IOException;
    }

    interface MonotonicClock {
        long nanoTime();
    }

    interface Cancellation {
        void cancel();
    }

    interface DeadlineScheduler {
        Cancellation schedule(Runnable task, long delayNanos);
    }

    private final ConnectionFactory connections;
    private final MonotonicClock clock;
    private final DeadlineScheduler scheduler;
    private final Set<Owner> inFlight = ConcurrentHashMap.newKeySet();
    /**
     * adm-F2: {@link #cancelInFlight()} is the close path of a per-Activity client, so it is
     * sticky. Once set, no later request of the same operation (for example the proof-bound
     * write after its challenge) may open a connection.
     */
    private volatile boolean closed;

    static AdminHttpRequestDeadline system() {
        return new AdminHttpRequestDeadline(
            url -> (HttpURLConnection) new URL(url).openConnection(),
            System::nanoTime,
            (task, delayNanos) -> {
                ScheduledFuture<?> future = DEADLINES.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            }
        );
    }

    AdminHttpRequestDeadline(
        ConnectionFactory connections,
        MonotonicClock clock,
        DeadlineScheduler scheduler
    ) {
        if (connections == null || clock == null || scheduler == null) {
            throw new IllegalArgumentException("HTTP connection, clock and deadline scheduler are required");
        }
        this.connections = connections;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    /**
     * R14 (adm-F1): overall budget of the one-time original image GET. The 20 s request
     * budget covers connecting and the headers; the body may take its size at 64 KiB/s.
     * The total never exceeds 150 s (the one-time grant itself lives at most 300 s).
     */
    static long originalEvidenceBudgetNanos(int expectedByteCount) {
        if (expectedByteCount < 1) {
            throw new IllegalArgumentException("original evidence size is invalid");
        }
        long transferNanos = (long) expectedByteCount * TimeUnit.SECONDS.toNanos(1)
            / ORIGINAL_EVIDENCE_FLOOR_BYTES_PER_SECOND;
        return Math.min(REQUEST_TIMEOUT_NANOS + transferNanos, ORIGINAL_EVIDENCE_MAX_TIMEOUT_NANOS);
    }

    /**
     * Opens one connection inside the 20 s request budget, arms its deadline alarm and applies
     * the existing connect/read phase timeouts. The caller must {@link Owner#close()} it in finally.
     */
    Owner open(String url) throws IOException {
        return open(null, url, REQUEST_TIMEOUT_NANOS);
    }

    /** Same as {@link #open(String)} with an explicit overall budget (original image only). */
    Owner open(String url, long budgetNanos) throws IOException {
        return open(null, url, budgetNanos);
    }

    Owner open(AdminRequestAdmission admission, String url) throws IOException {
        return open(admission, url, REQUEST_TIMEOUT_NANOS);
    }

    Owner open(AdminRequestAdmission admission, String url, long budgetNanos) throws IOException {
        if (budgetNanos < 1L || budgetNanos > ORIGINAL_EVIDENCE_MAX_TIMEOUT_NANOS) {
            throw new IllegalArgumentException("HTTP request budget is invalid");
        }
        // adm-F2: a closed transport opens nothing.
        if (closed) throw new IOException(CANCELLED_MESSAGE);
        long startedAtNanos = clock.nanoTime();
        HttpURLConnection connection = connections.open(url);
        Owner owner = new Owner(connection, startedAtNanos, budgetNanos);
        boolean armed = false;
        inFlight.add(owner);
        try {
            // adm-F2: a cancellation that ran before this registration was visible still wins.
            if (closed) owner.markCancelled();
            if (admission != null) owner.registration = admission.dispatch(owner::cancel, () -> {});
            owner.check();
            owner.alarm = scheduler.schedule(owner::expire, Math.max(0L, owner.remainingNanos()));
            owner.check();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            armed = true;
            return owner;
        } finally {
            if (!armed) owner.close();
        }
    }

    /**
     * Fails and disconnects every request still in flight and, sticky (adm-F2), every later
     * request of this transport. It never waits for them.
     */
    void cancelInFlight() {
        closed = true;
        for (Owner owner : inFlight) owner.cancel();
    }

    int inFlightCount() {
        return inFlight.size();
    }

    final class Owner implements AutoCloseable {
        private static final int ACTIVE = 0;
        private static final int TIMED_OUT = 1;
        private static final int CANCELLED = 2;
        private static final int FINISHED = 3;

        private final HttpURLConnection connection;
        private final long startedAtNanos;
        private final long budgetNanos;
        private final AtomicInteger state = new AtomicInteger(ACTIVE);
        private final AtomicBoolean disconnectClaimed = new AtomicBoolean();
        private volatile Cancellation alarm;
        private AdminRequestAdmission.Registration registration;

        private Owner(HttpURLConnection connection, long startedAtNanos, long budgetNanos) {
            this.connection = connection;
            this.startedAtNanos = startedAtNanos;
            this.budgetNanos = budgetNanos;
        }

        HttpURLConnection connection() {
            return connection;
        }

        long remainingNanos() {
            return budgetNanos - (clock.nanoTime() - startedAtNanos);
        }

        /** Throws once the deadline passed or the request was cancelled. */
        void check() throws IOException {
            if (remainingNanos() <= 0L) state.compareAndSet(ACTIVE, TIMED_OUT);
            int current = state.get();
            if (current == TIMED_OUT) throw new SocketTimeoutException(DEADLINE_MESSAGE);
            if (current == CANCELLED) throw new IOException(CANCELLED_MESSAGE);
        }

        private void expire() {
            if (state.compareAndSet(ACTIVE, TIMED_OUT)) disconnectAsync();
        }

        private void cancel() {
            if (state.compareAndSet(ACTIVE, CANCELLED)) disconnectAsync();
        }

        /** The caller's own thread fails the request; its finally disconnects synchronously. */
        private void markCancelled() {
            state.compareAndSet(ACTIVE, CANCELLED);
        }

        private void disconnectAsync() {
            // Best-effort platform cancellation must not block the scheduler or the caller.
            // The task captures this one connection, never a mutable "current connection".
            Thread cancellation = new Thread(this::disconnect, "walksafe-admin-http-cancel");
            cancellation.setDaemon(true);
            cancellation.start();
        }

        private void disconnect() {
            if (disconnectClaimed.compareAndSet(false, true)) connection.disconnect();
        }

        /**
         * Disconnects, then rejects a response whose cleanup finished after the deadline or a
         * cancellation, and finally cancels the alarm. A late success never escapes.
         */
        @Override
        public void close() throws IOException {
            try {
                disconnect();
            } finally {
                inFlight.remove(this);
                try {
                    check();
                    if (!state.compareAndSet(ACTIVE, FINISHED)) check();
                } finally {
                    Cancellation current = alarm;
                    try { if (current != null) current.cancel(); }
                    finally { if (registration != null) registration.close(); }
                }
            }
        }
    }
}
