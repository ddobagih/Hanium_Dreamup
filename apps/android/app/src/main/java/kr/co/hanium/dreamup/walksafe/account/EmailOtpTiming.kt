package kr.co.hanium.dreamup.walksafe.account

/**
 * S01-9: bounds for the email OTP enrollment times returned by the Gateway. Backend issues a
 * TTL of at most 1800 s (WALKSAFE_ACCOUNT_OTP_TTL_SECONDS) and a resend cooldown no longer
 * than that TTL, so a response is accepted only when
 * `now - skew < expiresAt <= now + 1800 s + skew` and `resendAvailableAt <= expiresAt`.
 */
internal object EmailOtpResponseBounds {
    const val MAX_TTL_MS = 30L * 60L * 1_000L
    const val CLOCK_SKEW_MS = 2L * 60L * 1_000L

    fun accepts(nowEpochMs: Long, expiresAtEpochMs: Long, resendAvailableAtEpochMs: Long): Boolean =
        nowEpochMs > CLOCK_SKEW_MS &&
            nowEpochMs <= Long.MAX_VALUE - MAX_TTL_MS - CLOCK_SKEW_MS &&
            expiresAtEpochMs > nowEpochMs - CLOCK_SKEW_MS &&
            expiresAtEpochMs <= nowEpochMs + MAX_TTL_MS + CLOCK_SKEW_MS &&
            resendAvailableAtEpochMs in 1L..expiresAtEpochMs
}

/**
 * S01-9: in-process OTP deadline measured on the monotonic elapsed-realtime clock, so moving
 * the device wall clock neither shortens nor extends the local validity window. The server
 * stays the authority; after a process restart only the wall-clock check is available.
 */
internal class EmailOtpDeadline private constructor(
    val expiresAtEpochMs: Long,
    private val receivedAtElapsedMs: Long,
    private val lifetimeMs: Long,
) {
    fun remainingMs(nowElapsedMs: Long): Long {
        val elapsed = nowElapsedMs - receivedAtElapsedMs
        if (elapsed < 0L) return 0L
        return (lifetimeMs - elapsed).coerceAtLeast(0L)
    }

    fun isOpenAt(nowElapsedMs: Long): Boolean = remainingMs(nowElapsedMs) > 0L

    companion object {
        fun received(
            expiresAtEpochMs: Long,
            receivedAtEpochMs: Long,
            receivedAtElapsedMs: Long,
        ): EmailOtpDeadline = EmailOtpDeadline(
            expiresAtEpochMs = expiresAtEpochMs,
            receivedAtElapsedMs = receivedAtElapsedMs,
            lifetimeMs = (expiresAtEpochMs - receivedAtEpochMs)
                .coerceIn(0L, EmailOtpResponseBounds.MAX_TTL_MS + EmailOtpResponseBounds.CLOCK_SKEW_MS),
        )
    }
}
