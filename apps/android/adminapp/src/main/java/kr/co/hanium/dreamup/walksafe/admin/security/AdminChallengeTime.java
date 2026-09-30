package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;

/** Estimates proof time from a wall-clock sample at challenge receipt and monotonic elapsed time. */
final class AdminChallengeTime {
    private final long receivedEpochMs;
    private final long receivedMonotonicNanos;

    AdminChallengeTime(long receivedEpochMs, long receivedMonotonicNanos) {
        this.receivedEpochMs = receivedEpochMs;
        this.receivedMonotonicNanos = receivedMonotonicNanos;
    }

    long estimatedNowEpochMs(long currentMonotonicNanos) throws IOException {
        if (receivedEpochMs < 0L) throw new IOException("device challenge receipt time is invalid");
        final long elapsedNanos;
        try {
            elapsedNanos = Math.subtractExact(currentMonotonicNanos, receivedMonotonicNanos);
        } catch (ArithmeticException error) {
            throw new IOException("device challenge elapsed time overflowed", error);
        }
        if (elapsedNanos < 0L) throw new IOException("device challenge elapsed time is negative");
        long elapsedMs = elapsedNanos / 1_000_000L;
        if (elapsedNanos % 1_000_000L != 0L) elapsedMs++;
        try {
            return Math.addExact(receivedEpochMs, elapsedMs);
        } catch (ArithmeticException error) {
            throw new IOException("device challenge estimated time overflowed", error);
        }
    }
}
