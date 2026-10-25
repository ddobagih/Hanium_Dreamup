package kr.co.hanium.dreamup.walksafe.navigation

import java.util.Locale
import java.util.TreeSet

/** Capture receipt time orders images across camera restarts without using wall clock time. */
internal data class TactileObservationFrameKey(
    val frameId: Long,
    val capturedAtElapsedRealtimeMs: Long,
)

data class TactileObservationFrameStatisticsSnapshot(
    /** Identified evaluation frames with completed tactile source candidates, since coordinator creation. */
    val candidateFrames: Long,
    /** Frames containing this reason at least once; different reasons may overlap in one frame. */
    val reasonFrames: Map<TactileObservationRejection, Long>,
) {
    fun diagnosticsText(): String {
        val ratios = reasonFrames.entries.sortedBy { it.key.ordinal }.joinToString(",") { (reason, count) ->
            val percentage = if (candidateFrames == 0L) 0.0 else 100.0 * count / candidateFrames
            "${reason.name.lowercase(Locale.ROOT)}:$count/$candidateFrames(" +
                String.format(Locale.ROOT, "%.1f", percentage) + "%)"
        }
        return "obsFrames=$candidateFrames reasonFrames=${ratios.ifEmpty { "-" }}"
    }
}

/** Bounded replay protection, including late detector results within the retained capture window. */
internal class TactileObservationFrameStatistics(private val retainedFrameLimit: Int = 64) {
    private val ordering = compareBy<TactileObservationFrameKey> { it.capturedAtElapsedRealtimeMs }
        .thenBy { it.frameId }
    private val recentFrames = TreeSet(ordering)
    private var retiredThrough: TactileObservationFrameKey? = null
    private var candidateFrames = 0L
    private val reasonFrames = linkedMapOf<TactileObservationRejection, Long>()

    init {
        require(retainedFrameLimit > 0)
    }

    @Synchronized
    fun record(
        key: TactileObservationFrameKey?,
        rejections: Collection<TactileObservationRejection>,
    ): TactileObservationFrameStatisticsSnapshot {
        if (key == null || key.frameId < 0L || key.capturedAtElapsedRealtimeMs < 0L ||
            retiredThrough?.let { ordering.compare(key, it) <= 0 } == true ||
            !recentFrames.add(key)
        ) return snapshot()

        candidateFrames += 1L
        rejections.toSet().forEach { reason -> reasonFrames[reason] = (reasonFrames[reason] ?: 0L) + 1L }
        if (recentFrames.size > retainedFrameLimit) retiredThrough = recentFrames.pollFirst()
        return snapshot()
    }

    @Synchronized
    fun snapshot(): TactileObservationFrameStatisticsSnapshot = TactileObservationFrameStatisticsSnapshot(
        candidateFrames = candidateFrames,
        reasonFrames = reasonFrames.toMap(),
    )
}
