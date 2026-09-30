package kr.co.hanium.dreamup.walksafe.feedback

/** One lifetime for physical outputs; a speech failure never renews an evidence lease. */
internal class FeedbackOutputGate(private val nowMs: () -> Long) {
    private var closed = false
    private var generation = 0L
    private var vibrationRank = Int.MIN_VALUE
    private var vibrationUntilMs = Long.MIN_VALUE

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun isCurrent(
        expectedGeneration: Long = generation,
        validUntilMs: Long = Long.MAX_VALUE,
        validator: () -> Boolean = { true },
    ): Boolean = !closed && generation == expectedGeneration && nowMs() <= validUntilMs &&
        runCatching(validator).getOrDefault(false) && !closed && generation == expectedGeneration &&
        nowMs() <= validUntilMs

    @Synchronized
    fun vibrate(
        pattern: LongArray?,
        rank: Int,
        expectedGeneration: Long,
        validUntilMs: Long,
        validator: () -> Boolean,
        output: (LongArray) -> Boolean,
    ): Boolean {
        if (pattern == null || pattern.isEmpty() || pattern.any { it < 0L } || pattern.none { it > 0L }) return false
        if (!isCurrent(expectedGeneration, validUntilMs, validator)) return false
        val now = nowMs()
        if (now > validUntilMs) return false
        if (now < vibrationUntilMs && rank < vibrationRank) return false
        if (!runCatching { output(pattern) }.getOrDefault(false)) return false
        vibrationRank = rank
        vibrationUntilMs = pattern.fold(now) { total, duration ->
            if (duration > Long.MAX_VALUE - total) Long.MAX_VALUE else total + duration
        }
        return true
    }

    @Synchronized
    fun close() {
        closed = true
        generation += 1L
        vibrationRank = Int.MIN_VALUE
        vibrationUntilMs = Long.MIN_VALUE
    }
}
