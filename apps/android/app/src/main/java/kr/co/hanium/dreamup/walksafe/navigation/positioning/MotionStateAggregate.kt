package kr.co.hanium.dreamup.walksafe.navigation.positioning

/**
 * Upper bounds (exclusive, ms) of the fixed state-transition latency buckets; the last bucket is
 * `>= 10 s`. Measurement resolution only, not an approved latency budget or pass criterion.
 */
val MOTION_LATENCY_BUCKET_UPPER_BOUNDS_MS: List<Long> = listOf(250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L)
val MOTION_LATENCY_BUCKET_COUNT: Int = MOTION_LATENCY_BUCKET_UPPER_BOUNDS_MS.size + 1

internal fun motionLatencyBucket(valueMs: Long): Int {
    val index = MOTION_LATENCY_BUCKET_UPPER_BOUNDS_MS.indexOfFirst { valueMs < it }
    return if (index < 0) MOTION_LATENCY_BUCKET_COUNT - 1 else index
}

/**
 * Non-sensitive aggregate of published motion states (ADD-MOTION-045): time per movement state,
 * transitions, reason frequencies, the unobserved share, and entry/release latency buckets. It
 * holds no coordinate, speed, sensor value, heading, or time series.
 */
data class MotionStateSummary(
    val stationaryMs: Long,
    val movingMs: Long,
    /** Time with no valid state: UNKNOWN published, or the previous state had already expired. */
    val unknownMs: Long,
    val snapshotCount: Int,
    val unknownSnapshotCount: Int,
    val transitionCount: Int,
    val reasonCounts: Map<UserMotionStateReason, Int>,
    /** Candidate start -> STATIONARY published. */
    val stationaryEntryLatencyHistogram: List<Int>,
    /** Latest step/GNSS evidence -> MOVING published after STATIONARY. */
    val stationaryReleaseLatencyHistogram: List<Int>,
) {
    init {
        require(stationaryMs >= 0L && movingMs >= 0L && unknownMs >= 0L)
        require(snapshotCount >= 0 && unknownSnapshotCount in 0..snapshotCount && transitionCount >= 0)
        require(reasonCounts.values.all { it > 0 })
        require(stationaryEntryLatencyHistogram.size == MOTION_LATENCY_BUCKET_COUNT)
        require(stationaryReleaseLatencyHistogram.size == MOTION_LATENCY_BUCKET_COUNT)
        require((stationaryEntryLatencyHistogram + stationaryReleaseLatencyHistogram).all { it >= 0 })
    }

    fun plus(other: MotionStateSummary): MotionStateSummary = MotionStateSummary(
        stationaryMs = Math.addExact(stationaryMs, other.stationaryMs),
        movingMs = Math.addExact(movingMs, other.movingMs),
        unknownMs = Math.addExact(unknownMs, other.unknownMs),
        snapshotCount = Math.addExact(snapshotCount, other.snapshotCount),
        unknownSnapshotCount = Math.addExact(unknownSnapshotCount, other.unknownSnapshotCount),
        transitionCount = Math.addExact(transitionCount, other.transitionCount),
        reasonCounts = (reasonCounts.keys + other.reasonCounts.keys).associateWith {
            Math.addExact(reasonCounts[it] ?: 0, other.reasonCounts[it] ?: 0)
        },
        stationaryEntryLatencyHistogram = stationaryEntryLatencyHistogram.zip(other.stationaryEntryLatencyHistogram) { a, b -> Math.addExact(a, b) },
        stationaryReleaseLatencyHistogram = stationaryReleaseLatencyHistogram.zip(other.stationaryReleaseLatencyHistogram) { a, b -> Math.addExact(a, b) },
    )
}

/**
 * Accumulates [UserMotionStateSnapshot]s only while collection is allowed (RAW consent + ACTIVE
 * walk, decided by the caller). A disallowed observation drops everything, so a pause, end, or
 * withdrawal never carries time across the gap. Not thread-safe: one owner thread.
 */
class MotionStateAggregate {
    private var previous: UserMotionStateSnapshot? = null
    private var stationaryMs = 0L
    private var movingMs = 0L
    private var unknownMs = 0L
    private var snapshots = 0
    private var unknownSnapshots = 0
    private var transitions = 0
    private val reasons = mutableMapOf<UserMotionStateReason, Int>()
    private val entry = IntArray(MOTION_LATENCY_BUCKET_COUNT)
    private val release = IntArray(MOTION_LATENCY_BUCKET_COUNT)

    /** Returns false (and forgets everything) when collection is not allowed. */
    fun observe(snapshot: UserMotionStateSnapshot?, collectionAllowed: Boolean): Boolean {
        if (!collectionAllowed) {
            reset()
            return false
        }
        if (snapshot == null) return false
        reasons[snapshot.reason] = saturatingAdd(reasons[snapshot.reason] ?: 0, 1)
        if (snapshot.reason == UserMotionStateReason.INVALID_TIME ||
            snapshot.reason == UserMotionStateReason.GENERATION_MISMATCH
        ) return true
        val last = previous
        if (last != null && snapshot.generation != last.generation) {
            // A new estimator generation (location/walk reset) is not a continuous interval.
            previous = null
        } else if (last != null && snapshot.evaluatedAtElapsedRealtimeMs >= last.evaluatedAtElapsedRealtimeMs) {
            val intervalMs = snapshot.evaluatedAtElapsedRealtimeMs - last.evaluatedAtElapsedRealtimeMs
            val stillValid = last.expiresAtElapsedRealtimeMs?.let {
                snapshot.evaluatedAtElapsedRealtimeMs <= it
            } == true
            when {
                !stillValid || last.movement == UserMovementState.UNKNOWN -> unknownMs = saturatingAdd(unknownMs, intervalMs)
                last.movement == UserMovementState.STATIONARY -> stationaryMs = saturatingAdd(stationaryMs, intervalMs)
                else -> movingMs = saturatingAdd(movingMs, intervalMs)
            }
            if (snapshot.movement != last.movement) {
                transitions = saturatingAdd(transitions, 1)
                recordLatency(last, snapshot)
            }
        } else if (last != null) {
            return true
        }
        snapshots = saturatingAdd(snapshots, 1)
        if (snapshot.movement == UserMovementState.UNKNOWN) unknownSnapshots = saturatingAdd(unknownSnapshots, 1)
        previous = snapshot
        return true
    }

    /** Null until at least one valid snapshot was observed in this window. */
    fun summary(): MotionStateSummary? = if (snapshots == 0 && reasons.isEmpty()) null else MotionStateSummary(
        stationaryMs = stationaryMs,
        movingMs = movingMs,
        unknownMs = unknownMs,
        snapshotCount = snapshots,
        unknownSnapshotCount = unknownSnapshots,
        transitionCount = transitions,
        reasonCounts = reasons.toMap(),
        stationaryEntryLatencyHistogram = entry.toList(),
        stationaryReleaseLatencyHistogram = release.toList(),
    )

    /** Starts the next window but keeps the last state so the boundary interval is counted. */
    fun resetWindow() {
        stationaryMs = 0L
        movingMs = 0L
        unknownMs = 0L
        snapshots = 0
        unknownSnapshots = 0
        transitions = 0
        reasons.clear()
        entry.fill(0)
        release.fill(0)
    }

    fun reset() {
        resetWindow()
        previous = null
    }

    private fun recordLatency(last: UserMotionStateSnapshot, next: UserMotionStateSnapshot) {
        val now = next.evaluatedAtElapsedRealtimeMs
        if (next.movement == UserMovementState.STATIONARY) {
            next.stationaryCandidateSinceElapsedRealtimeMs?.takeIf { it <= now }?.let {
                entry[motionLatencyBucket(now - it)] += 1
            }
        } else if (last.movement == UserMovementState.STATIONARY && next.movement == UserMovementState.MOVING) {
            listOfNotNull(next.stepObservedAtElapsedRealtimeMs, next.speedObservedAtElapsedRealtimeMs)
                .filter { it <= now }.maxOrNull()?.let { release[motionLatencyBucket(now - it)] += 1 }
        }
    }

    private fun saturatingAdd(left: Int, right: Int): Int =
        if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
