package kr.co.hanium.dreamup.walksafe.depth

data class DepthFrameSnapshot(
    val frameTimestampNs: Long,
    val rawDepth: DepthImage16?,
    val rawConfidence: ConfidenceImage8?,
    val fullDepth: DepthImage16?,
    val rawDepthTimestampNs: Long? = null,
    val cameraPoseEvidence: CameraPoseEvidence? = null,
    val fullDepthTimestampNs: Long? = null,
    // Actual CPU Image timestamp, distinct from ARCore's frame/pose and camera metadata clocks.
    val cameraImageTimestampNs: Long? = null,
    // Actual confidence Image clock; null preserves legacy callers without claiming a verified pair.
    val rawConfidenceTimestampNs: Long? = null,
) {
    val hasMetricRawDepth: Boolean
        get() = rawDepth != null && rawConfidence != null &&
            (rawConfidenceTimestampNs == null || rawConfidenceMatchesRawDepth)
    /** Strict same-observation evidence, independent of raw-versus-CPU freshness/reprojection. */
    val rawConfidenceMatchesRawDepth: Boolean
        get() {
            val raw = rawDepth ?: return false
            val confidence = rawConfidence ?: return false
            return rawConfidenceObservationMatches(
                rawDepthTimestampNs, rawConfidenceTimestampNs,
                raw.width, raw.height, confidence.width, confidence.height,
            )
        }
    val hasFullDepth: Boolean get() = fullDepth != null
    val hasFreshFullDepth: Boolean
        get() = hasFullDepth && matchesCameraImageTimestamp(fullDepthTimestampNs)
    val rawDepthMatchesCameraImage: Boolean
        get() = rawDepth != null && matchesCameraImageTimestamp(rawDepthTimestampNs)
    val hasFreshMetricRawDepth: Boolean
        get() = hasMetricRawDepth && rawDepthMatchesCameraImage
    val rawDepthFreshnessQuality: Float
        get() = if (rawDepthMatchesCameraImage) 1f else REPROJECTED_RAW_DEPTH_QUALITY

    private fun matchesCameraImageTimestamp(imageTimestampNs: Long?): Boolean =
        frameTimestampNs > 0L && cameraImageTimestampNs != null && cameraImageTimestampNs > 0L &&
            imageTimestampNs == cameraImageTimestampNs

    /** Reprojected raw pixels still describe this camera frame; only qualification needs new data. */
    fun validMetricSampleCount(
        minimumRawConfidence: Double,
        minimumDistanceMeters: Double,
        maximumDistanceMeters: Double,
        requireFreshRaw: Boolean = false,
    ): Int {
        val raw = rawDepth
        val confidence = rawConfidence
        var rawCount = 0
        if (
            hasMetricRawDepth && (!requireFreshRaw || hasFreshMetricRawDepth) &&
            raw != null && confidence != null &&
            raw.width == confidence.width && raw.height == confidence.height
        ) {
            for (index in 0 until raw.width * raw.height) {
                val distance = raw.millimeters[index] / 1_000.0
                val confidenceValue = (confidence.values[index].toInt() and 0xff) / 255.0
                if (
                    confidenceValue >= minimumRawConfidence &&
                    distance in minimumDistanceMeters..maximumDistanceMeters
                ) rawCount += 1
            }
        }
        val fullCount = fullDepth?.takeIf { hasFreshFullDepth }?.let { full ->
            (0 until full.width * full.height).count {
                full.millimeters[it] / 1_000.0 in minimumDistanceMeters..maximumDistanceMeters
            }
        } ?: 0
        return maxOf(rawCount, fullCount)
    }
}

fun ArCoreDepthBundle.toSnapshotAndClose(): DepthFrameSnapshot {
    return use { bundle ->
        val matchingRawPair = bundle.hasRawDepth
        DepthFrameSnapshot(
            frameTimestampNs = bundle.frameTimestampNs,
            rawDepth = bundle.rawDepth?.takeIf { matchingRawPair }?.toDepthImage16Snapshot(),
            rawConfidence = bundle.rawConfidence?.takeIf { matchingRawPair }?.toConfidenceImage8Snapshot(),
            fullDepth = bundle.fullDepth?.toDepthImage16Snapshot(),
            rawDepthTimestampNs = bundle.rawDepthTimestampNs,
            fullDepthTimestampNs = bundle.fullDepthTimestampNs,
            cameraImageTimestampNs = bundle.cameraImageTimestampNs,
            rawConfidenceTimestampNs = bundle.rawConfidenceTimestampNs ?: bundle.rawConfidence?.timestamp,
        )
    }
}

internal fun rawConfidenceObservationMatches(
    rawTimestampNs: Long?,
    confidenceTimestampNs: Long?,
    rawWidth: Int,
    rawHeight: Int,
    confidenceWidth: Int,
    confidenceHeight: Int,
): Boolean = rawTimestampNs != null && rawTimestampNs > 0L && confidenceTimestampNs == rawTimestampNs &&
    rawWidth > 0 && rawHeight > 0 && rawWidth == confidenceWidth && rawHeight == confidenceHeight

private const val REPROJECTED_RAW_DEPTH_QUALITY = 0.70f
