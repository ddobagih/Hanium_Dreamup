package kr.co.hanium.dreamup.walksafe.depth

data class DepthFrameSnapshot(
    val frameTimestampNs: Long,
    val rawDepth: DepthImage16?,
    val rawConfidence: ConfidenceImage8?,
    val fullDepth: DepthImage16?,
) {
    val hasMetricRawDepth: Boolean get() = rawDepth != null && rawConfidence != null
    val hasFullDepth: Boolean get() = fullDepth != null
}

fun ArCoreDepthBundle.toSnapshotAndClose(): DepthFrameSnapshot {
    return use { bundle ->
        DepthFrameSnapshot(
            frameTimestampNs = bundle.frameTimestampNs,
            rawDepth = bundle.rawDepth?.toDepthImage16Snapshot(),
            rawConfidence = bundle.rawConfidence?.toConfidenceImage8Snapshot(),
            fullDepth = bundle.fullDepth?.toDepthImage16Snapshot(),
        )
    }
}
