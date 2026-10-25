package kr.co.hanium.dreamup.walksafe.depth

/** One verified capture event, with each clock kept in its own domain. */
data class DepthObservationTime internal constructor(
    val source: DepthSource,
    val imageTimestampNs: Long,
    val frameTimestampMs: Long,
    val capturedAtElapsedRealtimeMs: Long?,
)

/** A present container with a null source means its original capture is unknown. */
data class DepthCaptureTimes internal constructor(
    val raw: DepthObservationTime?,
    val full: DepthObservationTime?,
)

/** Bounded capture history; Image timestamps identify events, never become Frame/elapsed clocks. */
internal class DepthCaptureClock {
    private val records = mutableMapOf<DepthSource, LinkedHashMap<Long, DepthObservationTime>>()
    private val latestIndependentImage = mutableMapOf<DepthSource, Long>()

    fun observe(snapshot: DepthFrameSnapshot, frameTimestampMs: Long, capturedAtElapsedRealtimeMs: Long?): DepthCaptureTimes {
        if (snapshot.frameTimestampNs <= 0L || frameTimestampMs < 0L ||
            snapshot.frameTimestampNs / 1_000_000L != frameTimestampMs ||
            capturedAtElapsedRealtimeMs?.let { it < 0L } == true
        ) return DepthCaptureTimes(null, null)
        return DepthCaptureTimes(
            observation(DepthSource.ARCORE_RAW_DEPTH, snapshot.rawDepthTimestampNs,
                snapshot.hasFreshMetricRawDepth, frameTimestampMs, capturedAtElapsedRealtimeMs),
            observation(DepthSource.ARCORE_FULL_DEPTH, snapshot.fullDepthTimestampNs,
                snapshot.hasFreshFullDepth, frameTimestampMs, capturedAtElapsedRealtimeMs),
        )
    }

    private fun observation(source: DepthSource, imageTimestampNs: Long?, sameCapture: Boolean,
                            frameTimestampMs: Long, capturedAtElapsedRealtimeMs: Long?): DepthObservationTime? {
        val imageId = imageTimestampNs?.takeIf { it > 0L } ?: return null
        val history = records.getOrPut(source) { LinkedHashMap() }
        val known = history[imageId]
        if (known != null) return known.takeIf {
            it.frameTimestampMs <= frameTimestampMs &&
                (capturedAtElapsedRealtimeMs == null || it.capturedAtElapsedRealtimeMs == null ||
                    it.capturedAtElapsedRealtimeMs <= capturedAtElapsedRealtimeMs)
        }
        // Eviction must not let an old/repeated Image ID seed a new capture time.
        if (!sameCapture || latestIndependentImage[source]?.let { imageId <= it } == true) return null
        val capture = DepthObservationTime(source, imageId, frameTimestampMs, capturedAtElapsedRealtimeMs)
        history[imageId] = capture
        latestIndependentImage[source] = imageId
        while (history.size > 64) history.remove(history.keys.first())
        return capture
    }
}

/** Direct pre-aligned estimator callers may assert the same capture, without an elapsed lease. */
internal fun ObjectDepthInput.depthObservationTime(source: DepthSource): DepthObservationTime? {
    val resolved = depthCaptureTimes
    if (resolved != null) return (when (source) {
        DepthSource.ARCORE_RAW_DEPTH -> resolved.raw
        DepthSource.ARCORE_FULL_DEPTH -> resolved.full
        else -> null
    })?.takeIf { it.source == source && it.frameTimestampMs in 0L..timestampMs }
    val imageId = when (source) {
        DepthSource.ARCORE_RAW_DEPTH -> rawDepthTimestampNs?.takeIf { rawDepthMatchesCameraFrame }
        DepthSource.ARCORE_FULL_DEPTH -> fullDepthTimestampNs?.takeIf { fullDepthMatchesCameraFrame }
        else -> null
    }?.takeIf { it > 0L && timestampMs >= 0L } ?: return null
    return DepthObservationTime(source, imageId, timestampMs, null)
}
