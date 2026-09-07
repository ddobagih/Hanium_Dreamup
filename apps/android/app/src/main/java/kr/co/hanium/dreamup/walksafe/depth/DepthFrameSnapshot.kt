package kr.co.hanium.dreamup.walksafe.depth

data class DepthFrameSnapshot(
    val frameTimestampNs: Long,
    val rawDepth: DepthImage16?,
    val rawConfidence: ConfidenceImage8?,
    val fullDepth: DepthImage16?,
    val rawDepthTimestampNs: Long? = null,
) {
    val hasMetricRawDepth: Boolean get() = rawDepth != null && rawConfidence != null
    val hasFullDepth: Boolean get() = fullDepth != null
    val rawDepthIsNewObservation: Boolean?
        get() = if (rawDepth == null || rawDepthTimestampNs == null) null else rawDepthTimestampNs == frameTimestampNs
    val hasFreshMetricRawDepth: Boolean
        get() = hasMetricRawDepth && rawDepthIsNewObservation != false
    val rawDepthFreshnessQuality: Float
        get() = if (rawDepthIsNewObservation == false) REPROJECTED_RAW_DEPTH_QUALITY else 1f
}

fun ArCoreDepthBundle.toSnapshotAndClose(): DepthFrameSnapshot {
    return use { bundle ->
        DepthFrameSnapshot(
            frameTimestampNs = bundle.frameTimestampNs,
            rawDepth = bundle.rawDepth?.toDepthImage16Snapshot(),
            rawConfidence = bundle.rawConfidence?.toConfidenceImage8Snapshot(),
            fullDepth = bundle.fullDepth?.toDepthImage16Snapshot(),
            rawDepthTimestampNs = bundle.rawDepthTimestampNs,
        )
    }
}

private const val REPROJECTED_RAW_DEPTH_QUALITY = 0.70f
